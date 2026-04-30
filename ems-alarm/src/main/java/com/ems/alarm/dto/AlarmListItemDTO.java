package com.ems.alarm.dto;

import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;

import java.time.OffsetDateTime;

public record AlarmListItemDTO(
        Long id,
        Long deviceId,
        String deviceCode,
        String deviceName,
        AlarmType alarmType,
        String severity,
        AlarmStatus status,
        OffsetDateTime triggeredAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime ackedAt
) {}
