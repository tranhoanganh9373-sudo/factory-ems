package com.ems.alarm.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AlarmMetricsTest {

    @Test
    void registers_allFiveMetrics() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AlarmMetrics metrics = new AlarmMetrics(registry);

        metrics.recordDetectorScan(Duration.ofMillis(80));
        metrics.setActive("silent_timeout", 7);
        metrics.setActive("consecutive_fail", 2);
        metrics.incrementCreated("silent_timeout");
        metrics.incrementResolved("auto");
        metrics.recordWebhookDelivery("success", 1, Duration.ofMillis(45));

        assertThat(registry.find("ems.alarm.detector.duration").timer().count()).isEqualTo(1L);

        assertThat(registry.find("ems.alarm.active.count").tag("type", "silent_timeout").gauge().value())
                .isEqualTo(7d);
        assertThat(registry.find("ems.alarm.active.count").tag("type", "consecutive_fail").gauge().value())
                .isEqualTo(2d);

        assertThat(registry.find("ems.alarm.created.total").tag("type", "silent_timeout").counter().count())
                .isEqualTo(1d);

        assertThat(registry.find("ems.alarm.resolved.total").tag("reason", "auto").counter().count())
                .isEqualTo(1d);

        assertThat(registry.find("ems.alarm.webhook.delivery.duration")
                .tag("outcome", "success").tag("attempt", "1").timer().count()).isEqualTo(1L);
    }

    @Test
    void setActive_withUnknownType_normalizesToOther() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AlarmMetrics metrics = new AlarmMetrics(registry);

        metrics.setActive("weird-type", 5);

        assertThat(registry.find("ems.alarm.active.count").tag("type", "other").gauge().value())
                .isEqualTo(5d);
    }

    @Test
    void incrementCreated_unknownType_normalizesToOther() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AlarmMetrics metrics = new AlarmMetrics(registry);
        metrics.incrementCreated("weird");
        assertThat(registry.find("ems.alarm.created.total").tag("type", "other").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void incrementResolved_unknownReason_normalizesToOther() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AlarmMetrics metrics = new AlarmMetrics(registry);
        metrics.incrementResolved("bogus");
        assertThat(registry.find("ems.alarm.resolved.total").tag("reason", "other").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void recordWebhookDelivery_clampsAttemptToMaxThree() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AlarmMetrics metrics = new AlarmMetrics(registry);

        metrics.recordWebhookDelivery("failure", 5, Duration.ofMillis(10));

        assertThat(registry.find("ems.alarm.webhook.delivery.duration")
                .tag("outcome", "failure").tag("attempt", "3").timer().count()).isEqualTo(1L);
    }
}
