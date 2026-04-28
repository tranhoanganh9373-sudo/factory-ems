package com.ems.collector.health;

import com.ems.audit.entity.AuditLog;
import com.ems.audit.repository.AuditLogRepository;
import com.ems.collector.poller.DeviceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmTransitionListenerTest {

    private AuditLogRepository repo;
    private AlarmTransitionListener listener;

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        listener = new AlarmTransitionListener(repo);
    }

    @Test
    void onTransition_writesAuditRow_withFromToInSummary() {
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        Instant at = Instant.parse("2026-04-28T10:00:00Z");
        listener.onTransition("dev-A", DeviceState.HEALTHY, DeviceState.DEGRADED,
                "cycle failed (attempts=4)", at);

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(cap.capture());
        AuditLog row = cap.getValue();
        assertThat(row.getAction()).isEqualTo("COLLECTOR_STATE_CHANGE");
        assertThat(row.getResourceType()).isEqualTo("COLLECTOR");
        assertThat(row.getResourceId()).isEqualTo("dev-A");
        assertThat(row.getSummary())
                .contains("HEALTHY")
                .contains("DEGRADED")
                .contains("cycle failed (attempts=4)");
        assertThat(row.getActorUsername()).isEqualTo("system");
        assertThat(row.getActorUserId()).isNull();
    }

    @Test
    void onTransition_repoThrows_doesNotPropagate() {
        when(repo.save(any(AuditLog.class)))
                .thenThrow(new RuntimeException("db down"));
        // Listener 必须吞错；不应让 poller transition 失败
        listener.onTransition("dev", DeviceState.HEALTHY, DeviceState.DEGRADED,
                "x", Instant.now());
    }

    @Test
    void onTransition_nullAt_usesNow() {
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        listener.onTransition("dev", DeviceState.UNREACHABLE, DeviceState.HEALTHY,
                "recovery", null);

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getOccurredAt()).isNotNull();
    }
}
