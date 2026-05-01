package com.ems.collector.service;

import com.ems.collector.config.ByteOrder;
import com.ems.collector.config.CollectorProperties;
import com.ems.collector.config.DataType;
import com.ems.collector.config.DeviceConfig;
import com.ems.collector.config.FunctionType;
import com.ems.collector.config.Parity;
import com.ems.collector.config.Protocol;
import com.ems.collector.config.RegisterConfig;
import com.ems.collector.config.RegisterKind;
import com.ems.collector.poller.DevicePoller;
import com.ems.collector.transport.ModbusIoException;
import com.ems.collector.transport.ModbusMaster;
import com.ems.collector.transport.ModbusMasterFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/** Phase F — reload diff/apply 语义 */
class CollectorServiceReloadTest {

    private CollectorService svc;

    @AfterEach
    void tearDown() {
        if (svc != null) svc.shutdown();
    }

    @Test
    @Timeout(5)
    void reload_addsNewDevice() {
        startWithDevices(device("d1", 100));
        await().atMost(2, SECONDS).until(() -> svc.pollers().size() == 1);

        var newProps = new CollectorProperties(true,
                List.of(device("d1", 100), device("d2", 100)));
        var result = svc.reload(newProps);

        assertThat(result.added()).containsExactly("d2");
        assertThat(result.removed()).isEmpty();
        assertThat(result.modified()).isEmpty();
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(svc.pollers().size()).isEqualTo(2);
    }

    @Test
    @Timeout(5)
    void reload_removesDevice() {
        startWithDevices(device("d1", 100), device("d2", 100));
        await().atMost(2, SECONDS).until(() -> svc.pollers().size() == 2);

        var newProps = new CollectorProperties(true, List.of(device("d1", 100)));
        var result = svc.reload(newProps);

        assertThat(result.removed()).containsExactly("d2");
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(svc.pollers().size()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void reload_modifiedDevice() {
        startWithDevices(device("d1", 100));

        // change polling interval → device equals returns false → 视作 modified
        var newProps = new CollectorProperties(true, List.of(device("d1", 200)));
        var result = svc.reload(newProps);

        assertThat(result.modified()).containsExactly("d1");
        assertThat(result.added()).isEmpty();
        assertThat(result.removed()).isEmpty();
        assertThat(svc.pollers().size()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void reload_unchangedDevice_isReportedAsUnchanged() {
        var same = device("d1", 100);
        startWithDevices(same);

        // 同样的 device 配置 (record equals)
        var newProps = new CollectorProperties(true, List.of(same));
        var result = svc.reload(newProps);

        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.added()).isEmpty();
        assertThat(result.modified()).isEmpty();
        assertThat(result.removed()).isEmpty();
    }

    @Test
    void reload_whenNotRunning_throws() {
        AtomicInteger reads = new AtomicInteger();
        ModbusMasterFactory factory = dev -> new AlwaysSuccessMaster(reads);
        svc = new CollectorService(
                new CollectorProperties(false, List.of(device("d1", 100))),
                r -> {}, factory, Clock.systemUTC(),
                DevicePoller.StateTransitionListener.NOOP, null, null, null, null
        );
        svc.start();

        assertThatThrownBy(() ->
                svc.reload(new CollectorProperties(true, List.of(device("d1", 100)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }

    @Test
    void reload_attemptingToDisable_throws() {
        startWithDevices(device("d1", 100));
        assertThatThrownBy(() ->
                svc.reload(new CollectorProperties(false, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enabled=false");
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    private void startWithDevices(DeviceConfig... devs) {
        AtomicInteger reads = new AtomicInteger();
        ModbusMasterFactory factory = dev -> new AlwaysSuccessMaster(reads);
        svc = new CollectorService(
                new CollectorProperties(true, List.of(devs)),
                r -> {}, factory, Clock.systemUTC(),
                DevicePoller.StateTransitionListener.NOOP, null, null, null, null
        );
        svc.start();
    }

    private static DeviceConfig device(String id, int pollingMs) {
        return new DeviceConfig(id, "MOCK-" + id, Protocol.TCP,
                "127.0.0.1", 502, null, null, null, null, Parity.NONE,
                1, pollingMs, Math.min(500, pollingMs - 1), 0, Math.max(pollingMs, 1000), 1000,
                List.of(new RegisterConfig("voltage", 0, 1,
                        FunctionType.HOLDING, DataType.UINT16, ByteOrder.ABCD,
                        BigDecimal.ONE, "V", "voltage", RegisterKind.GAUGE)));
    }

    static class AlwaysSuccessMaster implements ModbusMaster {
        private final AtomicInteger reads;
        private boolean connected;
        AlwaysSuccessMaster(AtomicInteger reads) { this.reads = reads; }
        @Override public void open() { connected = true; }
        @Override public void close() { connected = false; }
        @Override public boolean isConnected() { return connected; }
        @Override public byte[] readHolding(int u, int a, int c) {
            reads.incrementAndGet();
            return new byte[]{0x00, 0x64};
        }
        @Override public byte[] readInput(int u, int a, int c) { return readHolding(u, a, c); }
        @Override public boolean[] readCoils(int u, int a, int c) { return new boolean[c]; }
        @Override public boolean[] readDiscreteInputs(int u, int a, int c) { return new boolean[c]; }
    }
}
