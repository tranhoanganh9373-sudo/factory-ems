package com.ems.cost.dto;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/** Period for a dry-run preview (no persistence). */
public record DryRunReq(
        @NotNull OffsetDateTime periodStart,
        @NotNull OffsetDateTime periodEnd
) {}
