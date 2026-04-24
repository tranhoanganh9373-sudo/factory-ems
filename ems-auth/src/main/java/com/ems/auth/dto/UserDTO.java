package com.ems.auth.dto;
import java.time.OffsetDateTime;
import java.util.List;
public record UserDTO(
    Long id, String username, String displayName, Boolean enabled,
    List<String> roles, OffsetDateTime lastLoginAt, OffsetDateTime createdAt
) {}
