package com.ems.dashboard.controller;

import com.ems.core.dto.Result;
import com.ems.dashboard.dto.*;
import com.ems.dashboard.service.DashboardService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("isAuthenticated()")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) { this.service = service; }

    /** ① KPI 卡 */
    @GetMapping("/kpi")
    public Result<List<KpiDTO>> kpi(
            @RequestParam(defaultValue = "TODAY") RangeType range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Long orgNodeId,
            @RequestParam(required = false) String energyType) {
        return Result.ok(service.kpi(new RangeQuery(range, from, to, orgNodeId, energyType)));
    }

    /** ② 24h 实时曲线 */
    @GetMapping("/realtime-series")
    public Result<List<SeriesDTO>> realtimeSeries(
            @RequestParam(defaultValue = "LAST_24H") RangeType range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Long orgNodeId,
            @RequestParam(required = false) String energyType) {
        return Result.ok(service.realtimeSeries(new RangeQuery(range, from, to, orgNodeId, energyType)));
    }

    /** ③ 能源构成 */
    @GetMapping("/energy-composition")
    public Result<List<CompositionDTO>> energyComposition(
            @RequestParam(defaultValue = "TODAY") RangeType range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Long orgNodeId) {
        return Result.ok(service.energyComposition(new RangeQuery(range, from, to, orgNodeId, null)));
    }

    /** ④ 测点详情 */
    @GetMapping("/meter/{id}/detail")
    public Result<MeterDetailDTO> meterDetail(
            @PathVariable("id") Long id,
            @RequestParam(defaultValue = "TODAY") RangeType range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return Result.ok(service.meterDetail(id, new RangeQuery(range, from, to, null, null)));
    }

    /** ⑤ Top-N */
    @GetMapping("/top-n")
    public Result<List<TopNItemDTO>> topN(
            @RequestParam(defaultValue = "TODAY") RangeType range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Long orgNodeId,
            @RequestParam(required = false) String energyType,
            @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(service.topN(new RangeQuery(range, from, to, orgNodeId, energyType), limit));
    }
}
