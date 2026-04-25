package com.ems.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

public record CreateShiftReq(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 64) String name,
        @NotNull LocalTime timeStart,
        @NotNull LocalTime timeEnd,
        Integer sortOrder
) {}
