package com.ems.cost.dto;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Submit a new async cost-allocation run.
 * `ruleIds` is optional — null/empty means "run all active rules in the period".
 */
public record SubmitRunReq(
        @NotNull OffsetDateTime periodStart,
        @NotNull OffsetDateTime periodEnd,
        List<Long> ruleIds
) {}
