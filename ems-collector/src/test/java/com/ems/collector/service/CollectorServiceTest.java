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
import com.ems.collector.poller.DeviceReading;
import com.ems.collector.poller.DeviceState;
import com.ems.collector.transport.ModbusIoException;
import com.ems.collector.transport.ModbusMaster;
import com.ems.collector.transport.ModbusMasterFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class CollectorServiceTest {

    private CollectorService svc;

    @AfterEach
    void tearDown() {
        if (svc != null) svc.shutdown();
    }

    @Test
    void start_disabled_isNoop() {
        svc = build(false, List.of(device("d1", 100)));
        svc.start();
        assertThat(svc.isRunning()).isFalse();
        assertThat(svc.pollers()).isEmpty();
    }

    @Test
    void start_emptyDevices_runningButNoPollers() {
        svc = build(true, List.of());
        svc.start();
        assertThat(svc.isRunning()).isTrue();
        assertThat(svc.pollers()).isEmpty();
    }

    @Test
    @Timeout(5)
    void start_schedulesPollers_andSinkReceivesReadings() {
        ConcurrentLinkedQueue<DeviceReading> readings = new ConcurrentLinkedQueue<>();
        AtomicInteger reads = new AtomicInteger();
        ModbusMasterFactory factory = dev -> new AlwaysSuccessMaster(reads);

        svc = new CollectorService(
                new CollectorProperties(true, List.of(device("d1", 50))),
                readings::add,
                factory,
                Clock.systemUTC(),
                DevicePoller.StateTransitionListener.NOOP
        );
        svc.start();

        // Wait for at least 3 reads
        await().atMost(2, SECONDS).until(() -> reads.get() >= 3);
        assertThat(readings).isNotEmpty();
        assertThat(readings.peek().deviceId()).isEqualTo("d1");
    }

    @Test
    @Timeout(5)
    void multipleDevices_allPolled() {
        ConcurrentLinkedQueue<DeviceReading> readings = new ConcurrentLinkedQueue<>();
        AtomicInteger reads = new AtomicInteger();
        ModbusMasterFactory factory = dev -> new AlwaysSuccessMaster(reads);

        svc = new CollectorService(
                new CollectorProperties(true, List.of(
                        device("d1", 50),
                        device("d2", 50),
                        device("d3", 50)
                )),
                readings::add,
                factory,
                Clock.systemUTC(),
                DevicePoller.StateTransitionListener.NOOP
        );
        svc.start();

        await().atMost(3, SECONDS).until(() ->
                readings.stream().map(DeviceReading::deviceId).distinct().count() == 3);
    }

    @Test
    @Timeout(5)
    void shutdown_stopsScheduling_andClosesMasters() throws Exception {
        ConcurrentLinkedQueue<DeviceReading> readings = new ConcurrentLinkedQueue<>();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        ModbusMasterFactory factory = dev -> new AlwaysSuccessMaster(reads, closes);

        svc = new CollectorService(
                new CollectorProperties(true, List.of(device("d1", 50))),
                readings::add,
                factory,
                Clock.systemUTC(),
                DevicePoller.StateTransitionListener.NOOP
        );
        svc.start();
        await().atMost(2, SECONDS).until(() -> reads.get() >= 2);

        int readsAtShutdown = reads.get();
        svc.shutdown();
        assertThat(svc.isRunning()).isFalse();
        assertThat(closes.get()).isGreaterThanOrEqualTo(1);

        // No new reads after shutdown (allow +1 for in-flight read that completed during shutdown)
        Thread.sleep(300);
        assertThat(reads.get()).isLessThanOrEqualTo(readsAtShutdown + 1);
    }

    @Test
    @Timeout(5)
    void snapshots_preserveYamlOrder() {
        ModbusMasterFactory factory = dev -> new AlwaysSuccessMaster(new AtomicInteger());
        svc = new CollectorService(
                new CollectorProperties(true, List.of(
                        device("z-last", 100),
                        device("a-first", 100),
                        device("m-middle", 100)
                )),
                r -> {}, factory, Clock.systemUTC(),
                DevicePoller.StateTransitionListener.NOOP
        );
        svc.start();
        // immediate snapshot before any poll completes — ordering still preserved
        List<String> ids = svc.snapshots().stream().map(s -> s.deviceId()).toList();
        assertThat(ids).containsExactly("z-last", "a-first", "m-middle");
    }

    @Test
    @Timeout(5)
    void failingDevice_transitionsButOtherDeviceUnaffected() throws Exception {
        AtomicInteger d1Reads = new AtomicInteger();
        ModbusMasterFactory factory = dev -> dev.id().equals("dead")
                ? new AlwaysFailMaster()
                : new AlwaysSuccessMaster(d1Reads);

        svc = new CollectorService(
                new CollectorProperties(true, List.of(device("dead", 50), device("alive", 50))),
                r -> {}, factory, Clock.systemUTC(),
                DevicePoller.StateTransitionListener.NOOP
        );
        svc.start();
        await().atMost(3, SECONDS).until(() -> d1Reads.get() >= 3);

        DeviceState dead = svc.snapshots().stream()
                .filter(s -> s.deviceId().equals("dead")).findFirst().orElseThrow().state();
        DeviceState alive = svc.snapshots().stream()
                .filter(s -> s.deviceId().equals("alive")).findFirst().orElseThrow().state();
        assertThat(dead).isIn(DeviceState.DEGRADED, DeviceState.UNREACHABLE);
        assertThat(alive).isEqualTo(DeviceState.HEALTHY);
    }

    /* ── helpers ─────────────────────────────────────────────────────── */

    private static CollectorService build(boolean enabled, List<DeviceConfig> devs) {
        return new CollectorService(
                new CollectorProperties(enabled, devs),
                r -> {},
                dev -> new AlwaysSuccessMaster(new AtomicInteger()),
                Clock.systemUTC(),
                DevicePoller.StateTransitionListener.NOOP
        );
    }

    private static DeviceConfig device(String id, int pollingMs) {
        return new DeviceConfig(
                id, "MOCK-M-ELEC-" + id, Protocol.TCP,
                "127.0.0.1", 502, null, null, null, null, Parity.NONE,
                1, pollingMs, Math.min(500, pollingMs - 1), 0, Math.max(pollingMs, 1000), 1000,
                List.of(new RegisterConfig("voltage_a", 0, 2,
                        FunctionType.HOLDING, DataType.FLOAT32, ByteOrder.ABCD,
                        BigDecimal.ONE, "V", "voltage_a", RegisterKind.GAUGE))
        );
    }

    static class AlwaysSuccessMaster implements ModbusMaster {
        private final AtomicInteger reads;
        private final AtomicInteger closes;
        private boolean connected;

        AlwaysSuccessMaster(AtomicInteger reads) { this(reads, new AtomicInteger()); }
        AlwaysSuccessMaster(AtomicInteger reads, AtomicInteger closes) {
            this.reads = reads;
            this.closes = closes;
        }

        @Override public void open() { connected = true; }
        @Override public void close() { connected = false; closes.incrementAndGet(); }
        @Override public boolean isConnected() { return connected; }
        @Override public byte[] readHolding(int u, int a, int c) {
            reads.incrementAndGet();
            return new byte[]{0x40, (byte) 0x49, 0x0F, (byte) 0xD0};   // 3.14159f
        }
        @Override public byte[] readInput(int u, int a, int c) { return readHolding(u, a, c); }
        @Override public boolean[] readCoils(int u, int a, int c) { return new boolean[c]; }
        @Override public boolean[] readDiscreteInputs(int u, int a, int c) { return new boolean[c]; }
    }

    static class AlwaysFailMaster implements ModbusMaster {
        @Override public void open() throws ModbusIoException { throw new ModbusIoException("fail", true); }
        @Override public void close() {}
        @Override public boolean isConnected() { return false; }
        @Override public byte[] readHolding(int u, int a, int c) throws ModbusIoException { throw new ModbusIoException("fail", true); }
        @Override public byte[] readInput(int u, int a, int c) throws ModbusIoException { throw new ModbusIoException("fail", true); }
        @Override public boolean[] readCoils(int u, int a, int c) throws ModbusIoException { throw new ModbusIoException("fail", true); }
        @Override public boolean[] readDiscreteInputs(int u, int a, int c) throws ModbusIoException { throw new ModbusIoException("fail", true); }
    }
}
