package com.ems.collector.diagnostics;

import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.runtime.ConnectionState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 25 + Mockito 不能 mock ChannelStateRegistry，用真实实例 + fixed Clock。
 * SimpleMeterRegistry 是 in-memory 实现，可直接断言注册的 gauge。
 */
@DisplayName("CollectorTransport diagnostics beans")
class CollectorTransportDiagnosticsTest {

    private ChannelStateRegistry registry;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-30T10:00:00Z"), ZoneOffset.UTC);
        registry = new ChannelStateRegistry(clock, event -> {});
    }

    @Test
    @DisplayName("Health UP 当所有 channel 都 CONNECTED")
    void health_allConnected_isUp() {
        registry.register(1L, "VIRTUAL");
        registry.register(2L, "VIRTUAL");
        registry.setState(1L, ConnectionState.CONNECTED);
        registry.setState(2L, ConnectionState.CONNECTED);

        var ind = new CollectorTransportHealthIndicator(registry);
        var h = ind.health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("total", 2);
        assertThat(h.getDetails()).containsEntry("disconnected", 0L);
    }

    @Test
    @DisplayName("Health DEGRADED 当存在 ERROR / DISCONNECTED")
    void health_someDisconnected_isDegraded() {
        registry.register(1L, "VIRTUAL");
        registry.register(2L, "VIRTUAL");
        registry.setState(1L, ConnectionState.CONNECTED);
        registry.setState(2L, ConnectionState.ERROR);

        var ind = new CollectorTransportHealthIndicator(registry);
        var h = ind.health();

        assertThat(h.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(h.getDetails()).containsEntry("disconnected", 1L);
    }

    @Test
    @DisplayName("Metrics 注册 total + 每个 ConnectionState 一个 gauge")
    void metrics_registersGauges() {
        registry.register(1L, "VIRTUAL");
        registry.setState(1L, ConnectionState.CONNECTED);

        var meterRegistry = new SimpleMeterRegistry();
        new CollectorTransportMetrics(meterRegistry, registry).register();

        assertThat(meterRegistry.find("ems_collector_channels_total").gauge()).isNotNull();
        for (var s : ConnectionState.values()) {
            assertThat(meterRegistry.find("ems_collector_channels_state")
                    .tag("state", s.name()).gauge()).isNotNull();
        }
        assertThat(meterRegistry.find("ems_collector_channels_total")
                .gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.find("ems_collector_channels_state")
                .tag("state", "CONNECTED").gauge().value()).isEqualTo(1.0);
    }
}
