package com.ems.meter.controller;

import com.ems.core.dto.Result;
import com.ems.core.exception.ConflictException;
import com.ems.meter.dto.CarbonFactorDTO;
import com.ems.meter.dto.CreateCarbonFactorReq;
import com.ems.meter.entity.CarbonFactor;
import com.ems.meter.repository.CarbonFactorRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 碳排因子 (carbon-factor) 管理端点 —— append-only 模式。
 *
 * <p>历史行不可改不可删；调整因子时新增一条更晚的 effective_from 行。下游
 * {@code CarbonReportServiceImpl.findEffective(asOf)} 会自动取最新生效行。
 */
@RestController
@RequestMapping("/api/v1/carbon-factor")
public class CarbonFactorController {

    private final CarbonFactorRepository repo;

    public CarbonFactorController(CarbonFactorRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<CarbonFactorDTO>> list() {
        var rows = repo.findAllByOrderByRegionAscEnergySourceAscEffectiveFromDesc()
                .stream()
                .map(CarbonFactorDTO::from)
                .toList();
        return Result.ok(rows);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<CarbonFactorDTO>> create(@Valid @RequestBody CreateCarbonFactorReq req) {
        if (repo.existsByRegionAndEnergySourceAndEffectiveFrom(
                req.region(), req.energySource(), req.effectiveFrom())) {
            throw new ConflictException("已存在相同 (region, energy_source, effective_from) 的碳排因子记录");
        }
        CarbonFactor saved = repo.save(new CarbonFactor(
                req.region(),
                req.energySource(),
                req.effectiveFrom(),
                req.factorKgPerKwh()));
        return ResponseEntity.status(201).body(Result.ok(CarbonFactorDTO.from(saved)));
    }
}
