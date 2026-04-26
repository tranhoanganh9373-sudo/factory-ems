package com.ems.billing.controller;

import com.ems.billing.dto.CostDistributionDTO;
import com.ems.billing.service.BillingService;
import com.ems.core.dto.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

/**
 * Plan 2.2 Phase K — 看板面板 ⑩ 当月成本分布。
 * GET /api/v1/dashboard/cost-distribution?period=YYYY-MM
 *   period 为空 → 取库里最新 SUCCESS run（任意账期）。
 *   返回当次 run 的 (org, quantity, amount, percent) 用于饼图 + 表格。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class CostDashboardController {

    private final BillingService service;

    public CostDashboardController(BillingService service) {
        this.service = service;
    }

    @GetMapping("/cost-distribution")
    @PreAuthorize("isAuthenticated()")
    public Result<CostDistributionDTO> costDistribution(
            @RequestParam(value = "period", required = false) String period) {
        YearMonth ym = (period == null || period.isBlank()) ? null : YearMonth.parse(period);
        return Result.ok(service.costDistribution(ym));
    }
}
