package com.ems.alarm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WebhookConfigRequestDTO(
        boolean enabled,
        @NotBlank @Size(max = 512) String url,
        @Size(max = 255) String secret,             // null/empty = 不修改
        @Size(max = 32) String adapterType,         // null = "GENERIC_JSON"
        @Min(1000) @Max(30000) int timeoutMs
) {}
