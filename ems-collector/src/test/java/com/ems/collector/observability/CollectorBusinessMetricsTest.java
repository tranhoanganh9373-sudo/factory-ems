package com.ems.collector.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec §8.2 — 5 个 collector 业务 metrics 注册和写入正确性。
 */
class CollectorBusinessMetricsTest {

    @Test
    void registers_allFiveMetrics() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CollectorBusinessMetrics metrics = new CollectorBusinessMetrics(registry);

        metrics.recordPoll("modbus-tcp", Duration.ofMillis(120));
        metrics.setOnline(42);
        metrics.setOffline(3);
        metrics.recordReadSuccess("dev-1");
        metrics.recordReadFailure("dev-1", "timeout");

        assertThat(registry.find("ems.collector.poll.duration").tag("adapter", "modbus-tcp").timer())
                .isNotNull();
        assertThat(registry.find("ems.collector.poll.duration").tag("adapter", "modbus-tcp").timer().count())
                .isEqualTo(1L);

        assertThat(registry.find("ems.collector.devices.online").gauge().value()).isEqualTo(42d);
        assertThat(registry.find("ems.collector.devices.offline").gauge().value()).isEqualTo(3d);

        assertThat(registry.find("ems.collector.read.success.total").tag("device_id", "dev-1").counter().count())
                .isEqualTo(1d);
        assertThat(registry.find("ems.collector.read.failure.total")
                .tag("device_id", "dev-1").tag("reason", "timeout").counter().count()).isEqualTo(1d);
    }

    @Test
    void recordReadFailure_unknownReason_normalizesToOther() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CollectorBusinessMetrics metrics = new CollectorBusinessMetrics(registry);

        metrics.recordReadFailure("d", "weird-thing-not-in-enum");

        assertThat(registry.find("ems.collector.read.failure.total")
                .tag("device_id", "d").tag("reason", "other").counter().count()).isEqualTo(1d);
    }

    @Test
    void setOnline_setOffline_isAuthoritative_LastWriteWins() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CollectorBusinessMetrics metrics = new CollectorBusinessMetrics(registry);

        metrics.setOnline(10);
        metrics.setOnline(7);
        assertThat(registry.find("ems.collector.devices.online").gauge().value()).isEqualTo(7d);
    }
}
