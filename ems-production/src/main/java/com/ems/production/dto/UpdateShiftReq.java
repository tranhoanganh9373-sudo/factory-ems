package com.ems.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

public record UpdateShiftReq(
        @NotBlank String name,
        @NotNull LocalTime timeStart,
        @NotNull LocalTime timeEnd,
        Boolean enabled,
        Integer sortOrder
) {}
