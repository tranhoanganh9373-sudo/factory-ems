package com.ems.app.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppMetricsTest {

    @Test
    void incrementAudit_recordsCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AppMetrics metrics = new AppMetrics(registry);

        metrics.incrementAudit("USER_LOGIN");
        metrics.incrementAudit("USER_LOGIN");
        metrics.incrementAudit("ROLE_UPDATE");

        assertThat(registry.find("ems.app.audit.write.total").tag("action", "USER_LOGIN").counter().count())
                .isEqualTo(2d);
        assertThat(registry.find("ems.app.audit.write.total").tag("action", "ROLE_UPDATE").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void incrementException_recordsCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AppMetrics metrics = new AppMetrics(registry);

        metrics.incrementException("NullPointerException");
        metrics.incrementException("RuntimeException");

        assertThat(registry.find("ems.app.exception.total").tag("type", "NullPointerException").counter().count())
                .isEqualTo(1d);
        assertThat(registry.find("ems.app.exception.total").tag("type", "RuntimeException").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void incrementAudit_nullAction_normalizesToOther() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AppMetrics metrics = new AppMetrics(registry);
        metrics.incrementAudit(null);
        assertThat(registry.find("ems.app.audit.write.total").tag("action", "other").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void incrementException_nullType_normalizesToOther() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AppMetrics metrics = new AppMetrics(registry);
        metrics.incrementException(null);
        assertThat(registry.find("ems.app.exception.total").tag("type", "other").counter().count())
                .isEqualTo(1d);
    }
}
