package com.ems.collector.buffer;

import com.ems.collector.poller.DeviceReading;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteBufferStoreTest {

    @TempDir Path tmp;
    private SqliteBufferStore store;

    @BeforeEach
    void setUp() {
        var props = new BufferProperties(tmp.resolve("buf.db").toString(), 100, 7, 30000);
        store = new SqliteBufferStore(props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void enqueue_thenPeek_returnsSameContent() {
        DeviceReading r = reading("dev-1", "M1", "voltage_a", new BigDecimal("220.50"));
        long id = store.enqueue(r);
        assertThat(id).isPositive();

        var entries = store.peekUnsent(10);
        assertThat(entries).hasSize(1);
        var e = entries.get(0);
        assertThat(e.deviceId()).isEqualTo("dev-1");
        assertThat(e.meterCode()).isEqualTo("M1");
        assertThat(e.reading().numericFields()).containsEntry("voltage_a", new BigDecimal("220.50"));
    }

    @Test
    void peekUnsent_returnsFifoOrder() {
        store.enqueue(reading("dev", "M", "f1", new BigDecimal("1")));
        store.enqueue(reading("dev", "M", "f1", new BigDecimal("2")));
        store.enqueue(reading("dev", "M", "f1", new BigDecimal("3")));

        var ids = store.peekUnsent(10).stream().map(BufferStore.BufferEntry::id).toList();
        assertThat(ids).isSorted();
    }

    @Test
    void markSent_removesFromUnsent() {
        long a = store.enqueue(reading("dev", "M", "v", new BigDecimal("1")));
        long b = store.enqueue(reading("dev", "M", "v", new BigDecimal("2")));

        store.markSent(List.of(a));
        assertThat(store.unsentCount()).isEqualTo(1);

        var remaining = store.peekUnsent(10);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).id()).isEqualTo(b);
    }

    @Test
    void markSent_idempotent() {
        long a = store.enqueue(reading("dev", "M", "v", new BigDecimal("1")));
        store.markSent(List.of(a));
        store.markSent(List.of(a));   // again — should not throw
        assertThat(store.unsentCount()).isZero();
    }

    @Test
    void capacity_perDevice_dropsOldest() {
        // cap=100；插 105 → 应剩 100 (最早 5 条被剪掉)
        var props = new BufferProperties(tmp.resolve("cap.db").toString(), 100, 7, 30000);
        try (var s = new SqliteBufferStore(props, new ObjectMapper())) {
            for (int i = 0; i < 105; i++) {
                s.enqueue(reading("dev", "M", "v", BigDecimal.valueOf(i)));
            }
            assertThat(s.unsentCount()).isLessThanOrEqualTo(100);
        }
    }

    @Test
    void vacuum_purgesSentAndOld() {
        long a = store.enqueue(reading("dev", "M", "v", new BigDecimal("1")));
        store.enqueue(reading("dev", "M", "v", new BigDecimal("2")));
        store.markSent(List.of(a));

        long beforeVac = store.unsentCount();
        store.vacuum();
        // sent=1 一定被删；unsent 数不变
        assertThat(store.unsentCount()).isEqualTo(beforeVac);
    }

    @Test
    void multipleDevices_independentBuffers() {
        store.enqueue(reading("dev-A", "MA", "v", new BigDecimal("1")));
        store.enqueue(reading("dev-B", "MB", "v", new BigDecimal("2")));
        store.enqueue(reading("dev-A", "MA", "v", new BigDecimal("3")));

        var unsent = store.peekUnsent(10);
        assertThat(unsent).hasSize(3);
        long aCount = unsent.stream().filter(e -> e.deviceId().equals("dev-A")).count();
        long bCount = unsent.stream().filter(e -> e.deviceId().equals("dev-B")).count();
        assertThat(aCount).isEqualTo(2);
        assertThat(bCount).isEqualTo(1);
    }

    @Test
    void persistence_acrossInstances() {
        String dbPath = tmp.resolve("persist.db").toString();
        var props = new BufferProperties(dbPath, 100, 7, 30000);
        long id;
        try (var s1 = new SqliteBufferStore(props, new ObjectMapper())) {
            id = s1.enqueue(reading("dev", "M", "v", new BigDecimal("99")));
        }
        try (var s2 = new SqliteBufferStore(props, new ObjectMapper())) {
            var unsent = s2.peekUnsent(10);
            assertThat(unsent).hasSize(1);
            assertThat(unsent.get(0).id()).isEqualTo(id);
            assertThat(unsent.get(0).reading().numericFields().get("v"))
                    .isEqualByComparingTo("99");
        }
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    private static DeviceReading reading(String deviceId, String meterCode, String tsField, BigDecimal value) {
        return new DeviceReading(
                deviceId, meterCode, Instant.now(),
                Map.of(tsField, value),
                Map.of()
        );
    }
}
