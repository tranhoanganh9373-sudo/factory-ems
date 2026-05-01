package com.ems.alarm.dto;

import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.ResolvedReason;

import java.time.OffsetDateTime;
import java.util.Map;

public record AlarmDTO(
        Long id,
        Long deviceId,
        String deviceType,
        String deviceCode,
        String deviceName,
        AlarmType alarmType,
        String severity,
        AlarmStatus status,
        OffsetDateTime triggeredAt,
        OffsetDateTime ackedAt,
        Long ackedBy,
        OffsetDateTime resolvedAt,
        ResolvedReason resolvedReason,
        OffsetDateTime lastSeenAt,
        Map<String, Object> detail
) {}
