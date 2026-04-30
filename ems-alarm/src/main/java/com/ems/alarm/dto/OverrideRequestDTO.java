package com.ems.alarm.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record OverrideRequestDTO(
        @Positive Integer silentTimeoutSeconds,
        @Positive Integer consecutiveFailCount,
        boolean maintenanceMode,
        @Size(max = 255) String maintenanceNote
) {}
