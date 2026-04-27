package com.ems.collector.health;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorMetricsTest {

    private MeterRegistry registry;
    private CollectorMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CollectorMetrics(registry);
    }

    @Test
    void successCounter_incrementsPerDevice() {
        metrics.record("dev-A", true, 1_000_000L);
        metrics.record("dev-A", true, 1_000_000L);
        metrics.record("dev-B", true, 1_000_000L);

        assertThat(registry.get("ems.collector.read.success").tag("device", "dev-A")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get("ems.collector.read.success").tag("device", "dev-B")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void failureCounter_incrementsPerDevice() {
        metrics.record("dev-A", false, 1_000L);
        metrics.record("dev-A", false, 1_000L);

        assertThat(registry.get("ems.collector.read.failure").tag("device", "dev-A")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    void timer_recordsDuration() {
        metrics.record("dev-A", true, 5_000_000L);   // 5ms
        metrics.record("dev-A", true, 3_000_000L);   // 3ms

        var timer = registry.get("ems.collector.read.duration").tag("device", "dev-A").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(8.0);
    }

    @Test
    void successAndFailure_areSeparateMeters() {
        metrics.record("dev-A", true, 1_000L);
        metrics.record("dev-A", false, 1_000L);

        assertThat(registry.get("ems.collector.read.success").tag("device", "dev-A")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ems.collector.read.failure").tag("device", "dev-A")
                .counter().count()).isEqualTo(1.0);
    }
}
