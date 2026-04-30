package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.exception.AlarmStateException;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;

class AlarmStateMachineTest {

    private final AlarmStateMachine sm = new AlarmStateMachine();

    @Test
    void ack_fromActive_setsAckedAtAndBy() {
        Alarm a = newAlarm(AlarmStatus.ACTIVE);
        sm.ack(a, 42L);
        assertThat(a.getStatus()).isEqualTo(AlarmStatus.ACKED);
        assertThat(a.getAckedBy()).isEqualTo(42L);
        assertThat(a.getAckedAt()).isNotNull();
    }

    @Test
    void ack_fromResolved_throwsConflict() {
        Alarm a = newAlarm(AlarmStatus.RESOLVED);
        assertThatThrownBy(() -> sm.ack(a, 42L))
                .isInstanceOf(AlarmStateException.class)
                .hasMessageContaining("RESOLVED");
    }

    @Test
    void ack_fromAcked_throws() {
        Alarm a = newAlarm(AlarmStatus.ACKED);
        a.setAckedBy(7L);
        a.setAckedAt(OffsetDateTime.now());
        assertThatThrownBy(() -> sm.ack(a, 42L))
                .isInstanceOf(AlarmStateException.class)
                .hasMessageContaining("ACKED");
        assertThat(a.getAckedBy()).isEqualTo(7L);
    }

    @Test
    void resolve_fromActive_setsResolvedFields() {
        Alarm a = newAlarm(AlarmStatus.ACTIVE);
        sm.resolve(a, ResolvedReason.MANUAL);
        assertThat(a.getStatus()).isEqualTo(AlarmStatus.RESOLVED);
        assertThat(a.getResolvedReason()).isEqualTo(ResolvedReason.MANUAL);
        assertThat(a.getResolvedAt()).isNotNull();
    }

    @Test
    void resolve_fromAcked_ok() {
        Alarm a = newAlarm(AlarmStatus.ACKED);
        sm.resolve(a, ResolvedReason.AUTO);
        assertThat(a.getStatus()).isEqualTo(AlarmStatus.RESOLVED);
    }

    @Test
    void resolve_fromAlreadyResolved_throws() {
        Alarm a = newAlarm(AlarmStatus.RESOLVED);
        assertThatThrownBy(() -> sm.resolve(a, ResolvedReason.MANUAL))
                .isInstanceOf(AlarmStateException.class);
    }

    private Alarm newAlarm(AlarmStatus s) {
        Alarm a = new Alarm();
        a.setStatus(s);
        a.setTriggeredAt(OffsetDateTime.now());
        return a;
    }
}
