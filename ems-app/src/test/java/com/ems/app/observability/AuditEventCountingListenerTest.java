package com.ems.app.observability;

import com.ems.audit.event.AuditEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventCountingListenerTest {

    @Test
    void onAuditEvent_incrementsActionCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AppMetrics metrics = new AppMetrics(registry);
        AuditEventCountingListener listener = new AuditEventCountingListener(metrics);

        listener.onAuditEvent(sampleAuditEvent("USER_LOGIN"));
        listener.onAuditEvent(sampleAuditEvent("USER_LOGIN"));
        listener.onAuditEvent(sampleAuditEvent("ROLE_UPDATE"));

        assertThat(registry.find("ems.app.audit.write.total").tag("action", "USER_LOGIN").counter().count())
                .isEqualTo(2d);
        assertThat(registry.find("ems.app.audit.write.total").tag("action", "ROLE_UPDATE").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void onAuditEvent_nullAction_normalizesToOther() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AppMetrics metrics = new AppMetrics(registry);
        AuditEventCountingListener listener = new AuditEventCountingListener(metrics);

        listener.onAuditEvent(sampleAuditEvent(null));

        assertThat(registry.find("ems.app.audit.write.total").tag("action", "other").counter().count())
                .isEqualTo(1d);
    }

    private static AuditEvent sampleAuditEvent(String action) {
        return new AuditEvent(
                1L,
                "tester",
                action,
                "User",
                "1",
                "summary",
                null,
                "127.0.0.1",
                "junit",
                OffsetDateTime.now()
        );
    }
}
