package com.ems.cost.controller;

import com.ems.audit.aspect.AuditContext;
import com.ems.core.dto.Result;
import com.ems.cost.dto.CostLineDTO;
import com.ems.cost.dto.CostRunDTO;
import com.ems.cost.dto.DryRunReq;
import com.ems.cost.dto.SubmitRunReq;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.service.CostAllocationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Cost-allocation runs + dry-run preview.
 *
 * Reads (status, lines, dry-run preview) — any authenticated user.
 * Writes (submit run) — FINANCE / ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/cost")
public class CostAllocationController {

    private final CostAllocationService service;
    private final AuditContext auditContext;

    public CostAllocationController(CostAllocationService service, AuditContext auditContext) {
        this.service = service;
        this.auditContext = auditContext;
    }

    // -------------------- dry-run --------------------

    @PostMapping("/rules/{ruleId}/dry-run")
    @PreAuthorize("isAuthenticated()")
    public Result<List<CostLineDTO>> dryRunSingle(@PathVariable Long ruleId,
                                                  @Valid @RequestBody DryRunReq req) {
        List<CostAllocationLine> lines = service.dryRun(ruleId, req.periodStart(), req.periodEnd());
        return Result.ok(lines.stream().map(CostLineDTO::from).toList());
    }

    @PostMapping("/dry-run-all")
    @PreAuthorize("isAuthenticated()")
    public Result<List<CostLineDTO>> dryRunAll(@Valid @RequestBody DryRunReq req) {
        List<CostAllocationLine> lines = service.dryRunAll(req.periodStart(), req.periodEnd());
        return Result.ok(lines.stream().map(CostLineDTO::from).toList());
    }

    // -------------------- runs --------------------

    @PostMapping("/runs")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<Result<Map<String, Long>>> submit(@Valid @RequestBody SubmitRunReq req) {
        Long runId = service.submitRun(req.periodStart(), req.periodEnd(),
                req.ruleIds(), auditContext.currentUserId());
        return ResponseEntity.status(202).body(Result.ok(Map.of("runId", runId)));
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("isAuthenticated()")
    public Result<CostRunDTO> getRun(@PathVariable Long runId) {
        return Result.ok(CostRunDTO.from(service.getRun(runId)));
    }

    @GetMapping("/runs/{runId}/lines")
    @PreAuthorize("isAuthenticated()")
    public Result<List<CostLineDTO>> getLines(@PathVariable Long runId,
                                              @RequestParam(required = false) Long orgNodeId) {
        return Result.ok(service.getLines(runId, orgNodeId).stream().map(CostLineDTO::from).toList());
    }
}
