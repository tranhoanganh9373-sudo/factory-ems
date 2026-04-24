package com.ems.audit.event;

import java.time.OffsetDateTime;

public record AuditEvent(
    Long actorUserId,
    String actorUsername,
    String action,
    String resourceType,
    String resourceId,
    String summary,
    String detailJson,
    String ip,
    String userAgent,
    OffsetDateTime occurredAt
) {}
