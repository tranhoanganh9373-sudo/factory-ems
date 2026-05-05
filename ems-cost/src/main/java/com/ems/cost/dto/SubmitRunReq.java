package com.ems.cost.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Submit a new async cost-allocation run.
 *
 * `billPeriodId` 是必填；后端从 bill_period 直接读 (periodStart, periodEnd)，避免
 * 调用方拼 ISO UTC 时间戳与账期 +08:00 边界产生时区错配（issue #24）。
 *
 * `ruleIds` 是可选 — null/empty means "run all active rules in the period".
 */
public record SubmitRunReq(
        @NotNull Long billPeriodId,
        List<Long> ruleIds
) {}
