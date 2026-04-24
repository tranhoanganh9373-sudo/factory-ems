package com.ems.auth.dto;
import java.time.OffsetDateTime;
public record NodePermissionDTO(Long id, Long userId, Long orgNodeId,
                                String scope, OffsetDateTime createdAt) {}
