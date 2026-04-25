package com.ems.timeseries.rollup.controller;

import com.ems.core.dto.Result;
import com.ems.timeseries.rollup.RollupBackfillService;
import com.ems.timeseries.rollup.dto.BackfillReq;
import com.ems.timeseries.rollup.dto.BackfillResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ops/rollup")
public class RollupOpsController {

    private final RollupBackfillService backfill;

    public RollupOpsController(RollupBackfillService backfill) { this.backfill = backfill; }

    @PostMapping("/rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<BackfillResult> rebuild(@Valid @RequestBody BackfillReq req) {
        return Result.ok(backfill.rebuild(req));
    }
}
