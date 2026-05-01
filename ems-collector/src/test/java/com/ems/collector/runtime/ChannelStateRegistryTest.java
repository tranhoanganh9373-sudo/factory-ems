package com.ems.collector.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ChannelStateRegistryTest {

    private final Clock fixed = Clock.fixed(Instant.parse("2026-04-30T10:00:00Z"), ZoneOffset.UTC);
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final ChannelStateRegistry registry = new ChannelStateRegistry(fixed, publisher);

    @Test
    void register_newChannel_initializesConnectingState() {
        registry.register(1L, "VIRTUAL");

        var state = registry.snapshot(1L);

        assertThat(state).isNotNull();
        assertThat(state.protocol()).isEqualTo("VIRTUAL");
        assertThat(state.connState()).isEqualTo(ConnectionState.CONNECTING);
    }

    @Test
    void recordSuccess_afterRegister_setsConnectedAndIncrementsCounter() {
        registry.register(1L, "VIRTUAL");

        registry.recordSuccess(1L, 42L);

        var state = registry.snapshot(1L);
        assertThat(state.connState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(state.successCount24h()).isEqualTo(1);
        assertThat(state.avgLatencyMs()).isEqualTo(42L);
        assertThat(state.lastSuccessAt()).isNotNull();
    }

    @Test
    void recordFailure_afterRegister_setsDisconnectedAndStoresError() {
        registry.register(1L, "MODBUS_TCP");

        registry.recordFailure(1L, "connection refused");

        var state = registry.snapshot(1L);
        assertThat(state.connState()).isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(state.failureCount24h()).isEqualTo(1);
        assertThat(state.lastErrorMessage()).contains("connection refused");
    }

    @Test
    void recordFailure_truncatesLongErrorMessage() {
        registry.register(1L, "MQTT");
        var longMsg = "x".repeat(500);

        registry.recordFailure(1L, longMsg);

        assertThat(registry.snapshot(1L).lastErrorMessage()).hasSize(200);
    }

    @Test
    void unregister_removesStateFromRegistry() {
        registry.register(1L, "VIRTUAL");

        registry.unregister(1L);

        assertThat(registry.snapshot(1L)).isNull();
    }

    @Test
    void snapshotAll_returnsAllRegisteredChannels() {
        registry.register(1L, "VIRTUAL");
        registry.register(2L, "MODBUS_TCP");

        assertThat(registry.snapshotAll()).hasSize(2);
    }

    @Test
    void recordSuccess_unregisteredChannel_isNoop() {
        registry.recordSuccess(99L, 10L);

        assertThat(registry.snapshot(99L)).isNull();
    }

    // ── 事件发布相关 ─────────────────────────────────────────────────────────

    @Test
    void recordFailure_thresholdHits_publishesExactlyOneFailureEvent() {
        registry.register(1L, "MODBUS_TCP");

        for (int i = 0; i < ChannelStateRegistry.FAILURE_THRESHOLD; i++) {
            registry.recordFailure(1L, "io error #" + i);
        }

        List<ChannelFailureEvent> failures = publisher.eventsOf(ChannelFailureEvent.class);
        assertThat(failures).hasSize(1);
        ChannelFailureEvent ev = failures.get(0);
        assertThat(ev.channelId()).isEqualTo(1L);
        assertThat(ev.protocol()).isEqualTo("MODBUS_TCP");
        assertThat(ev.consecutiveFailures()).isEqualTo(ChannelStateRegistry.FAILURE_THRESHOLD);
        assertThat(ev.errorMessage()).contains("io error");
        assertThat(ev.occurredAt()).isEqualTo(Instant.parse("2026-04-30T10:00:00Z"));
    }

    @Test
    void recordFailure_belowThreshold_publishesNothing() {
        registry.register(1L, "VIRTUAL");

        for (int i = 0; i < ChannelStateRegistry.FAILURE_THRESHOLD - 1; i++) {
            registry.recordFailure(1L, "tmp");
        }

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void recordSuccess_afterFault_publishesRecoveredEvent() {
        registry.register(1L, "MQTT");
        for (int i = 0; i < ChannelStateRegistry.FAILURE_THRESHOLD; i++) {
            registry.recordFailure(1L, "down");
        }

        registry.recordSuccess(1L, 12L);

        assertThat(publisher.eventsOf(ChannelFailureEvent.class)).hasSize(1);
        List<ChannelRecoveredEvent> recovered = publisher.eventsOf(ChannelRecoveredEvent.class);
        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).channelId()).isEqualTo(1L);
    }

    @Test
    void recordSuccess_withoutPriorFault_doesNotPublishRecovered() {
        registry.register(1L, "VIRTUAL");
        registry.recordSuccess(1L, 10L);

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void recordFailure_intermittentBelowThreshold_neverPublishes() {
        registry.register(1L, "MODBUS_TCP");

        for (int i = 0; i < 4; i++) registry.recordFailure(1L, "fail");
        registry.recordSuccess(1L, 5L);
        for (int i = 0; i < 4; i++) registry.recordFailure(1L, "fail");

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void recordFailure_listenerThrows_doesNotPropagate() {
        ApplicationEventPublisher throwing = ev -> { throw new RuntimeException("listener boom"); };
        ChannelStateRegistry r = new ChannelStateRegistry(fixed, throwing);
        r.register(1L, "MODBUS_TCP");

        for (int i = 0; i < ChannelStateRegistry.FAILURE_THRESHOLD - 1; i++) {
            r.recordFailure(1L, "x");
        }

        // 第 5 次会触发 publish；listener 抛异常但 recordFailure 不能崩
        assertThatCode(() -> r.recordFailure(1L, "x")).doesNotThrowAnyException();
        // 后续调用仍能正常累加（不会卡死）
        assertThatCode(() -> r.recordFailure(1L, "x")).doesNotThrowAnyException();
    }

    @Test
    void recordFailure_secondFaultCycle_publishesAgainAfterRecovery() {
        registry.register(1L, "MODBUS_TCP");
        for (int i = 0; i < ChannelStateRegistry.FAILURE_THRESHOLD; i++) registry.recordFailure(1L, "down");
        registry.recordSuccess(1L, 10L);
        // 第二轮故障
        for (int i = 0; i < ChannelStateRegistry.FAILURE_THRESHOLD; i++) registry.recordFailure(1L, "down again");

        assertThat(publisher.eventsOf(ChannelFailureEvent.class)).hasSize(2);
        assertThat(publisher.eventsOf(ChannelRecoveredEvent.class)).hasSize(1);
    }

    /** 简单事件捕获器，避免 mock。线程安全，因 ChannelStateRegistry 在生产里也跨线程调用。 */
    private static final class RecordingPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();

        @Override
        public synchronized void publishEvent(Object event) {
            events.add(event);
        }

        @SuppressWarnings("unchecked")
        synchronized <T> List<T> eventsOf(Class<T> type) {
            List<T> out = new ArrayList<>();
            for (Object e : events) if (type.isInstance(e)) out.add((T) e);
            return out;
        }
    }
}
