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
    @DisplayName("reopen success → recordSuccess and poll proceeds in same cycle")
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
            verify(registry, atLeastOnce()).recordSuccess(eq(8L), anyLong());
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
