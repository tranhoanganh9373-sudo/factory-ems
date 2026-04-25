package com.ems.report.dto;

import java.time.Instant;

/** 异步导出令牌响应。POST /async 与 GET /file/{token}（pending/running/failed 状态）共用。 */
public record FileTokenDTO(
    String token,
    String status,
    String filename,
    Instant createdAt,
    Instant expiresAt,
    Long bytes,
    String error
) {}
