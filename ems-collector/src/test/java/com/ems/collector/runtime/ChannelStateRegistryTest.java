package com.ems.collector.runtime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelStateRegistryTest {

    private final Clock fixed = Clock.fixed(Instant.parse("2026-04-30T10:00:00Z"), ZoneOffset.UTC);
    private final ChannelStateRegistry registry = new ChannelStateRegistry(fixed);

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
}
