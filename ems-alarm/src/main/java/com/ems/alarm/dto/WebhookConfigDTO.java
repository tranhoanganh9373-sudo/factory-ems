package com.ems.alarm.dto;

import java.time.OffsetDateTime;

public record WebhookConfigDTO(
        boolean enabled,
        String url,
        String secret,        // 已脱敏：返回 "***" 或空字符串
        String adapterType,
        int timeoutMs,
        OffsetDateTime updatedAt
) {}
