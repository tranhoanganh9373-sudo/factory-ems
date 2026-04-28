package com.ems.collector.buffer;

import com.ems.collector.poller.DeviceReading;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQLite 实现的 {@link BufferStore}。
 *
 * <p>Schema (单表)：
 * <pre>{@code
 * CREATE TABLE collector_buffer (
 *   id           INTEGER PRIMARY KEY AUTOINCREMENT,
 *   device_id    TEXT NOT NULL,
 *   meter_code   TEXT NOT NULL,
 *   payload_json TEXT NOT NULL,
 *   created_at   INTEGER NOT NULL,    -- ms epoch
 *   sent         INTEGER NOT NULL DEFAULT 0
 * );
 * CREATE INDEX idx_device_unsent ON collector_buffer(device_id, sent, id);
 * CREATE INDEX idx_created       ON collector_buffer(created_at);
 * }</pre>
 *
 * <p>持久化策略：WAL + busy_timeout=5000；单连接 + synchronized 方法。collector 写速率
 * &lt;&lt; 1000 行/秒，单连接顺序写够用。
 *
 * <p>Payload 用 Jackson 序列化 {@link DeviceReading} (timestamp + numericFields + booleanFields)。
 */
public class SqliteBufferStore implements BufferStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteBufferStore.class);

    private final BufferProperties props;
    private final ObjectMapper mapper;
    private final Connection conn;

    public SqliteBufferStore(BufferProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper.copy().findAndRegisterModules();
        this.conn = openConnection(props.path());
        ensureSchema();
    }

    private static Connection openConnection(String path) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                Files.createDirectories(parent.toPath());
            }
            String url = "jdbc:sqlite:" + f.getAbsolutePath();
            Connection c = DriverManager.getConnection(url);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA synchronous=NORMAL");
            }
            return c;
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Failed to open SQLite buffer at " + path, e);
        }
    }

    private void ensureSchema() {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS collector_buffer (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        device_id TEXT NOT NULL,
                        meter_code TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        sent INTEGER NOT NULL DEFAULT 0
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_device_unsent ON collector_buffer(device_id, sent, id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_created ON collector_buffer(created_at)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to init buffer schema", e);
        }
    }

    @Override
    public synchronized long enqueue(DeviceReading reading) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO collector_buffer(device_id, meter_code, payload_json, created_at) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            String json = serialize(reading);
            ps.setString(1, reading.deviceId());
            ps.setString(2, reading.meterCode());
            ps.setString(3, json);
            ps.setLong(4, reading.timestamp().toEpochMilli());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    enforceCap(reading.deviceId());
                    return rs.getLong(1);
                }
            }
            throw new IllegalStateException("INSERT did not return id");
        } catch (SQLException e) {
            throw new IllegalStateException("buffer enqueue failed", e);
        }
    }

    /** 单设备超 maxRowsPerDevice → 删最早未发的。 */
    private void enforceCap(String deviceId) throws SQLException {
        long cap = props.maxRowsPerDevice();
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM collector_buffer WHERE id IN ("
                        + "  SELECT id FROM collector_buffer WHERE device_id=? ORDER BY id DESC LIMIT -1 OFFSET ?"
                        + ")")) {
            ps.setString(1, deviceId);
            ps.setLong(2, cap);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.warn("buffer cap reached for device {} — pruned {} oldest rows", deviceId, deleted);
            }
        }
    }

    @Override
    public synchronized List<BufferEntry> peekUnsent(int limit) {
        List<BufferEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, device_id, meter_code, payload_json, created_at FROM collector_buffer "
                        + "WHERE sent=0 ORDER BY id ASC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BufferEntry(
                            rs.getLong(1),
                            rs.getString(2),
                            rs.getString(3),
                            deserialize(rs.getString(4), rs.getString(2), rs.getString(3),
                                    Instant.ofEpochMilli(rs.getLong(5)))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("buffer peekUnsent failed", e);
        }
        return out;
    }

    @Override
    public synchronized void markSent(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        StringBuilder sql = new StringBuilder("UPDATE collector_buffer SET sent=1 WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) sql.append(i == 0 ? "?" : ",?");
        sql.append(")");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("buffer markSent failed", e);
        }
    }

    @Override
    public synchronized long unsentCount() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM collector_buffer WHERE sent=0")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("buffer unsentCount failed", e);
        }
    }

    @Override
    public synchronized void vacuum() {
        long ttlMs = props.ttlDays() * 86_400_000L;
        long cutoff = System.currentTimeMillis() - ttlMs;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM collector_buffer WHERE created_at < ? OR sent=1")) {
            ps.setLong(1, cutoff);
            int n = ps.executeUpdate();
            if (n > 0) {
                log.info("buffer vacuum pruned {} rows (sent or older than {} days)", n, props.ttlDays());
            }
        } catch (SQLException e) {
            log.warn("buffer vacuum failed: {}", e.toString());
        }
    }

    @Override
    public synchronized void close() {
        try { conn.close(); } catch (SQLException e) {
            log.warn("buffer close: {}", e.toString());
        }
    }

    /* ── serde ────────────────────────────────────────────────────────── */

    private record SerializedReading(Map<String, BigDecimal> num, Map<String, Boolean> bits) {}

    private String serialize(DeviceReading r) {
        try {
            return mapper.writeValueAsString(new SerializedReading(r.numericFields(), r.booleanFields()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("buffer serialize failed", e);
        }
    }

    private DeviceReading deserialize(String json, String deviceId, String meterCode, Instant ts) {
        try {
            SerializedReading sr = mapper.readValue(json, new TypeReference<>() {});
            return new DeviceReading(deviceId, meterCode, ts, sr.num(), sr.bits());
        } catch (IOException e) {
            throw new IllegalStateException("buffer deserialize failed", e);
        }
    }
}
