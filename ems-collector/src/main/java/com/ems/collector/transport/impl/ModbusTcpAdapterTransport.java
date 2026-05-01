package com.ems.collector.transport.impl;

import com.ems.collector.codec.RegisterDecoder;
import com.ems.collector.config.ByteOrder;
import com.ems.collector.config.DataType;
import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.protocol.ModbusPoint;
import com.ems.collector.protocol.ModbusTcpConfig;
import com.ems.collector.transport.ModbusIoException;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.SampleSink;
import com.ems.collector.transport.TcpModbusMaster;
import com.ems.collector.transport.TestResult;
import com.ems.collector.transport.Transport;
import com.ems.collector.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Modbus TCP Transport — 复用既有 {@link TcpModbusMaster}（j2mod 包装）+ {@link RegisterDecoder}。
 *
 * <p>线程模型：
 * <ul>
 *   <li>{@link #start} 同步连接 → 启动单线程 daemon ScheduledExecutorService</li>
 *   <li>调度器按 {@link ModbusTcpConfig#pollInterval()} 周期轮询 points，每个 point 一次 read 调用</li>
 *   <li>读失败仅 log + 推送 BAD quality sample，不中断调度</li>
 *   <li>{@link #stop} shutdownNow + close master，幂等</li>
 * </ul>
 *
 * <p>不在本类内做指数退避重连（CP-Phase 7 由 ChannelService + ChannelStateRegistry 上层管理）。
 */
public final class ModbusTcpAdapterTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(ModbusTcpAdapterTransport.class);
    private static final int DEFAULT_TIMEOUT_MS = 1000;
    private static final int TEST_TIMEOUT_MS = 2000;
    private static final int TEST_TIMEOUT_CAP_MS = 10_000;

    private TcpModbusMaster master;
    private ScheduledExecutorService scheduler;
    private volatile boolean connected = false;

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        if (!(config instanceof ModbusTcpConfig cfg)) {
            throw new TransportException("expected ModbusTcpConfig, got " + config.getClass().getSimpleName());
        }
        int timeoutMs = cfg.timeout() != null ? (int) cfg.timeout().toMillis() : DEFAULT_TIMEOUT_MS;
        master = new TcpModbusMaster(cfg.host(), cfg.port(), timeoutMs);
        try {
            master.open();
            connected = true;
        } catch (ModbusIoException e) {
            master = null;
            throw new TransportException("Modbus TCP connect failed: " + e.getMessage(), e);
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "modbus-tcp-" + channelId);
            t.setDaemon(true);
            return t;
        });
        long periodMs = cfg.pollInterval().toMillis();
        scheduler.scheduleAtFixedRate(
                () -> pollAll(channelId, cfg, sink),
                0L, periodMs, TimeUnit.MILLISECONDS);
        log.info("Modbus TCP transport started: channel={} {}:{} unitId={} interval={}ms points={}",
                channelId, cfg.host(), cfg.port(), cfg.unitId(), periodMs, cfg.points().size());
    }

    private void pollAll(Long channelId, ModbusTcpConfig cfg, SampleSink sink) {
        for (ModbusPoint p : cfg.points()) {
            try {
                Object value = readPoint(cfg.unitId(), p);
                sink.accept(new Sample(channelId, p.key(), Instant.now(),
                        value, Quality.GOOD, Map.of()));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("Modbus read failed for channel={} point={}: {}", channelId, p.key(), msg);
                sink.accept(new Sample(channelId, p.key(), Instant.now(),
                        null, Quality.BAD, Map.of("error", msg)));
            }
        }
    }

    private Object readPoint(int unitId, ModbusPoint p) throws ModbusIoException {
        String kind = p.registerKind() == null ? "" : p.registerKind().toUpperCase();
        return switch (kind) {
            case "HOLDING" -> decodeRegisterRead(
                    master.readHolding(unitId, p.address(), p.quantity()), p);
            case "INPUT" -> decodeRegisterRead(
                    master.readInput(unitId, p.address(), p.quantity()), p);
            case "COIL" -> {
                boolean[] bits = master.readCoils(unitId, p.address(), p.quantity());
                yield bits.length > 0 ? bits[0] : null;
            }
            case "DISCRETE_INPUT" -> {
                boolean[] bits = master.readDiscreteInputs(unitId, p.address(), p.quantity());
                yield bits.length > 0 ? bits[0] : null;
            }
            default -> throw new ModbusIoException("unsupported registerKind: " + p.registerKind(), false);
        };
    }

    private static BigDecimal decodeRegisterRead(byte[] raw, ModbusPoint p) {
        DataType dt = DataType.valueOf(p.dataType());
        ByteOrder order = p.byteOrder() == null ? ByteOrder.ABCD : ByteOrder.valueOf(p.byteOrder());
        BigDecimal scale = p.scale() == null ? null : BigDecimal.valueOf(p.scale());
        return RegisterDecoder.decode(raw, dt, order, scale);
    }

    @Override
    public void stop() {
        connected = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (master != null) {
            try {
                master.close();
            } catch (Exception e) {
                log.warn("Modbus TCP master close error: {}", e.toString());
            }
            master = null;
        }
    }

    @Override
    public boolean isConnected() {
        return connected && master != null && master.isConnected();
    }

    @Override
    public TestResult testConnection(ChannelConfig config) {
        if (!(config instanceof ModbusTcpConfig cfg)) {
            return TestResult.fail("expected ModbusTcpConfig");
        }
        long start = System.currentTimeMillis();
        int timeoutMs = cfg.timeout() != null
                ? Math.min((int) cfg.timeout().toMillis(), TEST_TIMEOUT_CAP_MS)
                : TEST_TIMEOUT_MS;
        try (TcpModbusMaster m = new TcpModbusMaster(cfg.host(), cfg.port(), timeoutMs)) {
            m.open();
            return TestResult.ok(System.currentTimeMillis() - start);
        } catch (Exception e) {
            return TestResult.fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
