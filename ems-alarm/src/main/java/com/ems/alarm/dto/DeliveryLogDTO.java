package com.ems.alarm.dto;

import com.ems.alarm.entity.DeliveryStatus;

import java.time.OffsetDateTime;

public record DeliveryLogDTO(
        Long id,
        Long alarmId,
        int attempts,
        DeliveryStatus status,
        String lastError,
        Integer responseStatus,
        Integer responseMs,
        OffsetDateTime createdAt
) {}
