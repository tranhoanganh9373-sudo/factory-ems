package com.ems.auth.dto;
import java.time.OffsetDateTime;
public record AuditLogDTO(
    Long id, Long actorUserId, String actorUsername,
    String action, String resourceType, String resourceId,
    String summary, String detail, String ip, String userAgent, OffsetDateTime occurredAt
) {}
