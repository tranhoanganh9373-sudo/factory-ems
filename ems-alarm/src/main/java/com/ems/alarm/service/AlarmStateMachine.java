package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.exception.AlarmStateException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class AlarmStateMachine {

    public void ack(Alarm a, Long userId) {
        if (a.getStatus() != AlarmStatus.ACTIVE) {
            throw new AlarmStateException("Cannot ack alarm in status " + a.getStatus());
        }
        a.setStatus(AlarmStatus.ACKED);
        a.setAckedAt(OffsetDateTime.now());
        a.setAckedBy(userId);
    }

    public void resolve(Alarm a, ResolvedReason reason) {
        if (a.getStatus() == AlarmStatus.RESOLVED) {
            throw new AlarmStateException("Already resolved");
        }
        a.setStatus(AlarmStatus.RESOLVED);
        a.setResolvedAt(OffsetDateTime.now());
        a.setResolvedReason(reason);
    }
}
