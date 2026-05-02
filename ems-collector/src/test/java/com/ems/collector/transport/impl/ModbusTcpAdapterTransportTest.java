package com.ems.collector.transport.impl;

import com.ems.collector.protocol.ModbusPoint;
import com.ems.collector.protocol.ModbusTcpConfig;
import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.transport.ModbusIoException;
import com.ems.collector.transport.ModbusSlaveTestFixture;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.TcpModbusMaster;
import com.ems.collector.transport.TestResult;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 使用进程内 j2mod ModbusSlaveTestFixture，避免 Docker testcontainers 依赖（CP-1.1 已确认本地不兼容）。
 */
class ModbusTcpAdapterTransportTest {

    @Test
    void readsHoldingRegisterAndPushesSample() throws Exception {
        try (ModbusSlaveTestFixture slave = ModbusSlaveTestFixture.start(1)) {
            slave.setHoldingRegister(0, (short) 0x1234);

            ModbusTcpConfig cfg = new ModbusTcpConfig(
                    "127.0.0.1", slave.port(), slave.unitId(),
                    Duration.ofMillis(200), Duration.ofMillis(1000),
                    List.of(new ModbusPoint("p1", "HOLDING", 0, 1, "INT16",
                            "ABCD", null, "kW")));

            ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
            ModbusTcpAdapterTransport t = new ModbusTcpAdapterTransport();
            try {
                t.start(99L, cfg, samples::add);
                Awaitility.await().atMost(3, TimeUnit.SECONDS)
                        .until(() -> !samples.isEmpty());

                Sample s = samples.peek();
                assertThat(s).isNotNull();
                assertThat(s.channelId()).isEqualTo(99L);
                assertThat(s.pointKey()).isEqualTo("p1");
                assertThat(s.quality()).isEqualTo(Quality.GOOD);
                assertThat(((BigDecimal) s.value()).intValueExact()).isEqualTo(0x1234);
                assertThat(t.isConnected()).isTrue();
            } finally {
                t.stop();
            }
        }
    }

    @Test
    void testConnectionSucceedsAgainstFixture() throws Exception {
        try (ModbusSlaveTestFixture slave = ModbusSlaveTestFixture.start(1)) {
            ModbusTcpConfig cfg = new ModbusTcpConfig(
                    "127.0.0.1", slave.port(), slave.unitId(),
                    Duration.ofSeconds(1), Duration.ofMillis(1000),
                    List.of(new ModbusPoint("p1", "HOLDING", 0, 1, "INT16",
                            "ABCD", null, null)));

            ModbusTcpAdapterTransport t = new ModbusTcpAdapterTransport();
            TestResult r = t.testConnection(cfg);

            assertThat(r.success()).isTrue();
            assertThat(r.latencyMs()).isNotNull();
        }
    }

    @Test
    void testConnectionReportsFailureForUnreachableHost() {
        ModbusTcpConfig cfg = new ModbusTcpConfig(
                "127.0.0.1", 1, 1,
                Duration.ofSeconds(1), Duration.ofMillis(500),
                List.of(new ModbusPoint("p1", "HOLDING", 0, 1, "INT16",
                        "ABCD", null, null)));

        ModbusTcpAdapterTransport t = new ModbusTcpAdapterTransport();
        TestResult r = t.testConnection(cfg);

        assertThat(r.success()).isFalse();
        assertThat(r.message()).isNotBlank();
    }

    // -------- Reconnect / backoff (Phase 1: 自动重连退避) ---------------------------

    private static ModbusTcpConfig fastPollCfg() {
        return new ModbusTcpConfig(
                "127.0.0.1", 1, 1,
                Duration.ofMillis(100), Duration.ofMillis(500),
                List.of(new ModbusPoint("p1", "HOLDING", 0, 1, "INT16",
                        "ABCD", null, "kW")));
    }

    @Test
    @DisplayName("master disconnected at cycle start → close + new master + reopen")
    void pollAll_masterDisconnected_attemptsReopen() throws Exception {
        ChannelStateRegistry registry = mock(ChannelStateRegistry.class);

        TcpModbusMaster m1 = mock(TcpModbusMaster.class);
        // m1 is "connected" at start() (open path) then drops on next isConnected() check.
        when(m1.isConnected()).thenReturn(true, false);
        when(m1.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x01});

        TcpModbusMaster m2 = mock(TcpModbusMaster.class);
        when(m2.isConnected()).thenReturn(true);
        when(m2.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x02});

        AtomicInteger created = new AtomicInteger();
        Supplier<TcpModbusMaster> factory = () -> created.incrementAndGet() == 1 ? m1 : m2;

        ModbusTcpAdapterTransport t = new ModbusTcpAdapterTransport(registry, factory);
        ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
        try {
            t.start(7L, fastPollCfg(), samples::add);
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> created.get() >= 2);
            verify(m1, atLeastOnce()).close();
            verify(m2, atLeastOnce()).open();
        } finally {
            t.stop();
        }
    }

    @Test
    @DisplayName("reopen success → poll proceeds in same cycle (success counted via sample sink, not transport)")
    void pollAll_reopenSucceeds_resetsAttemptCounterAndProceedsToPoll() throws Exception {
        ChannelStateRegistry registry = mock(ChannelStateRegistry.class);

        // m1: start()'s open() succeeds, but pollAll's isConnected() check fails on
        // cycle 1 → triggers reopen path → m2 takes over within the same cycle.
        TcpModbusMaster m1 = mock(TcpModbusMaster.class);
        when(m1.isConnected()).thenReturn(false);
        when(m1.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x05});

        TcpModbusMaster m2 = mock(TcpModbusMaster.class);
        when(m2.isConnected()).thenReturn(true);
        when(m2.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x06});

        AtomicInteger created = new AtomicInteger();
        Supplier<TcpModbusMaster> factory = () -> created.incrementAndGet() == 1 ? m1 : m2;

        // Long pollInterval (5s) — if recovery + GOOD sample don't both happen in cycle 1
        // (which fires immediately at t=0 due to scheduleWithFixedDelay initialDelay=0),
        // we'd wait another 5s for cycle 2. Asserting the GOOD sample arrives well under
        // 5s proves polling happened in the same cycle as the reopen.
        Duration longInterval = Duration.ofSeconds(5);
        ModbusTcpConfig cfg = new ModbusTcpConfig(
                "127.0.0.1", 1, 1,
                longInterval, Duration.ofMillis(500),
                List.of(new ModbusPoint("p1", "HOLDING", 0, 1, "INT16",
                        "ABCD", null, "kW")));

        ModbusTcpAdapterTransport t = new ModbusTcpAdapterTransport(registry, factory);
        ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
        long startNs = System.nanoTime();
        try {
            t.start(8L, cfg, samples::add);
            // First reopen attempt sleeps backoff (1s); GOOD sample should arrive shortly after.
            // Cap at 3s — well under one pollInterval (5s) — to prove same-cycle polling.
            Awaitility.await().atMost(3, TimeUnit.SECONDS).until(() ->
                    samples.stream().anyMatch(s -> s.quality() == Quality.GOOD));
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            assertThat(elapsedMs)
                    .as("first GOOD sample must arrive within first poll cycle (proves same-cycle polling)")
                    .isLessThan(longInterval.toMillis());
            // 注意：recordSuccess 不再由 transport 在重连成功时调用——而是 sample sink 收到 GOOD
            // sample 后由 ChannelService 的 cycle 聚合器上报。这里 sink 是 ConcurrentLinkedQueue，
            // 没有走聚合器路径，所以 registry 上不期望直接出现 recordSuccess 调用。
        } finally {
            t.stop();
        }
    }

    @Test
    @DisplayName("reopen failure → recordFailure, point not polled this cycle")
    void pollAll_reopenFails_incrementsAttemptsAndReturnsThisCycle() throws Exception {
        ChannelStateRegistry registry = mock(ChannelStateRegistry.class);

        TcpModbusMaster m1 = mock(TcpModbusMaster.class);
        when(m1.isConnected()).thenReturn(true, false, false);
        when(m1.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x01});

        TcpModbusMaster failing = mock(TcpModbusMaster.class);
        when(failing.isConnected()).thenReturn(false);
        doThrow(new ModbusIoException("connect refused", true)).when(failing).open();

        AtomicInteger created = new AtomicInteger();
        Supplier<TcpModbusMaster> factory = () -> created.incrementAndGet() == 1 ? m1 : failing;

        ModbusTcpAdapterTransport t = new ModbusTcpAdapterTransport(registry, factory);
        ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
        try {
            t.start(9L, fastPollCfg(), samples::add);
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> created.get() >= 2);
            verify(registry, atLeastOnce()).recordFailure(eq(9L), anyString());
            verify(failing, never()).readHolding(anyInt(), anyInt(), anyInt());
        } finally {
            t.stop();
        }
    }

    @Test
    @DisplayName("stop() during backoff sleep wakes promptly via Thread.interrupt")
    void stop_duringBackoffSleep_wakesPromptly() throws Exception {
        ChannelStateRegistry registry = mock(ChannelStateRegistry.class);

        // Initial master fails its isConnected() check on cycle 1 → forces ensureReopened.
        // The factory then returns a master whose open() always throws, so the scheduler
        // thread enters the backoff Thread.sleep loop. We then call stop() and assert
        // it returns well before the full 1s backoff completes.
        TcpModbusMaster m1 = mock(TcpModbusMaster.class);
        when(m1.isConnected()).thenReturn(false);

        TcpModbusMaster failing = mock(TcpModbusMaster.class);
        when(failing.isConnected()).thenReturn(false);
        doThrow(new ModbusIoException("refused", true)).when(failing).open();

        AtomicInteger created = new AtomicInteger();
        Supplier<TcpModbusMaster> factory = () -> created.incrementAndGet() == 1 ? m1 : failing;

        ModbusTcpAdapterTransport t = new ModbusTcpAdapterTransport(registry, factory);
        t.start(11L, fastPollCfg(), s -> {});
        // Let the first reopen attempt fail and the scheduler enter backoff sleep.
        Thread.sleep(200);
        long stopStart = System.nanoTime();
        t.stop();
        long stopElapsedMs = (System.nanoTime() - stopStart) / 1_000_000L;
        // Backoff sleep at attempt 1 is 1000 ms. If interrupt didn't propagate, stop()
        // would block until the sleep finishes. 500 ms cap leaves comfortable headroom.
        assertThat(stopElapsedMs)
                .as("stop() must wake the scheduler thread out of backoff sleep")
                .isLessThan(500L);
    }

    @Test
    @DisplayName("all-point IOException force-closes master so next cycle reconnects")
    void pollAll_allPointsFail_forceClosesMaster() throws Exception {
        ChannelStateRegistry registry = mock(ChannelStateRegistry.class);

        TcpModbusMaster m1 = mock(TcpModbusMaster.class);
        when(m1.isConnected()).thenReturn(true);
        when(m1.readHolding(anyInt(), anyInt(), anyInt()))
                .thenThrow(new ModbusIoException("read failed", true));

        TcpModbusMaster m2 = mock(TcpModbusMaster.class);
        when(m2.isConnected()).thenReturn(true);
        when(m2.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x01});

        AtomicInteger created = new AtomicInteger();
        Supplier<TcpModbusMaster> factory = () -> created.incrementAndGet() == 1 ? m1 : m2;

        ModbusTcpAdapterTransport t = new ModbusTcpAdapterTransport(registry, factory);
        ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
        try {
            t.start(10L, fastPollCfg(), samples::add);
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> created.get() >= 2);
            verify(m1, atLeastOnce()).close();
        } finally {
            t.stop();
        }
    }
}
