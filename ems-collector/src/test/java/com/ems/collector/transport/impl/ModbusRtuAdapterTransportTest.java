package com.ems.collector.transport.impl;

import com.ems.collector.protocol.ModbusPoint;
import com.ems.collector.protocol.ModbusRtuConfig;
import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.transport.ModbusIoException;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.RtuModbusMaster;
import com.ems.collector.transport.Sample;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
 * Reconnect/backoff TDD for {@link ModbusRtuAdapterTransport}. Mirrors the TCP test
 * but injects mocked {@link RtuModbusMaster} via the package-private supplier ctor —
 * we have no in-process serial fixture.
 */
@DisplayName("ModbusRtuAdapterTransport — auto reconnect with backoff")
class ModbusRtuAdapterTransportTest {

    private static ModbusRtuConfig fastPollCfg() {
        return new ModbusRtuConfig(
                "/dev/null", 9600, 8, 1, "NONE", 1,
                Duration.ofMillis(100), Duration.ofMillis(500),
                List.of(new ModbusPoint("p1", "HOLDING", 0, 1, "INT16",
                        "ABCD", null, "kW")));
    }

    @Test
    @DisplayName("master disconnected at cycle start → close + new master + reopen")
    void pollAll_masterDisconnected_attemptsReopen() throws Exception {
        ChannelStateRegistry registry = mock(ChannelStateRegistry.class);

        RtuModbusMaster m1 = mock(RtuModbusMaster.class);
        when(m1.isConnected()).thenReturn(true, false);
        when(m1.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x01});

        RtuModbusMaster m2 = mock(RtuModbusMaster.class);
        when(m2.isConnected()).thenReturn(true);
        when(m2.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x02});

        AtomicInteger created = new AtomicInteger();
        Supplier<RtuModbusMaster> factory = () -> created.incrementAndGet() == 1 ? m1 : m2;

        ModbusRtuAdapterTransport t = new ModbusRtuAdapterTransport(registry, factory);
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

        RtuModbusMaster m1 = mock(RtuModbusMaster.class);
        when(m1.isConnected()).thenReturn(true, false);
        when(m1.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x05});

        RtuModbusMaster m2 = mock(RtuModbusMaster.class);
        when(m2.isConnected()).thenReturn(true);
        when(m2.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x06});

        AtomicInteger created = new AtomicInteger();
        Supplier<RtuModbusMaster> factory = () -> created.incrementAndGet() == 1 ? m1 : m2;

        ModbusRtuAdapterTransport t = new ModbusRtuAdapterTransport(registry, factory);
        ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
        try {
            t.start(8L, fastPollCfg(), samples::add);
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
                    samples.stream().filter(s -> s.quality() == Quality.GOOD).count() >= 2);
            verify(registry, atLeastOnce()).recordSuccess(eq(8L), anyLong());
        } finally {
            t.stop();
        }
    }

    @Test
    @DisplayName("reopen failure → recordFailure, point not polled this cycle")
    void pollAll_reopenFails_incrementsAttemptsAndReturnsThisCycle() throws Exception {
        ChannelStateRegistry registry = mock(ChannelStateRegistry.class);

        RtuModbusMaster m1 = mock(RtuModbusMaster.class);
        when(m1.isConnected()).thenReturn(true, false, false);
        when(m1.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x01});

        RtuModbusMaster failing = mock(RtuModbusMaster.class);
        when(failing.isConnected()).thenReturn(false);
        doThrow(new ModbusIoException("port busy", true)).when(failing).open();

        AtomicInteger created = new AtomicInteger();
        Supplier<RtuModbusMaster> factory = () -> created.incrementAndGet() == 1 ? m1 : failing;

        ModbusRtuAdapterTransport t = new ModbusRtuAdapterTransport(registry, factory);
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

        RtuModbusMaster m1 = mock(RtuModbusMaster.class);
        when(m1.isConnected()).thenReturn(true);
        when(m1.readHolding(anyInt(), anyInt(), anyInt()))
                .thenThrow(new ModbusIoException("read failed", true));

        RtuModbusMaster m2 = mock(RtuModbusMaster.class);
        when(m2.isConnected()).thenReturn(true);
        when(m2.readHolding(anyInt(), anyInt(), anyInt())).thenReturn(new byte[]{0x00, 0x01});

        AtomicInteger created = new AtomicInteger();
        Supplier<RtuModbusMaster> factory = () -> created.incrementAndGet() == 1 ? m1 : m2;

        ModbusRtuAdapterTransport t = new ModbusRtuAdapterTransport(registry, factory);
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
