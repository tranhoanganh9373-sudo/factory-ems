package com.ems.meter.controller;

import com.ems.core.dto.Result;
import com.ems.core.exception.ConflictException;
import com.ems.meter.dto.CreateFeedInTariffReq;
import com.ems.meter.dto.FeedInTariffDTO;
import com.ems.meter.entity.FeedInTariff;
import com.ems.meter.repository.FeedInTariffRepository;
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
 * 上网电价 (feed-in-tariff) 管理端点 —— append-only 模式。
 *
 * <p>历史行不可改不可删；调价时新增一条更晚的 effective_from 行。下游
 * {@code FeedInRevenueServiceImpl.findEffective(asOf)} 会自动取最新生效行，故无需改动业务逻辑。
 */
@RestController
@RequestMapping("/api/v1/feed-in-tariff")
public class FeedInTariffController {

    private final FeedInTariffRepository repo;

    public FeedInTariffController(FeedInTariffRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<FeedInTariffDTO>> list() {
        var rows = repo.findAllByOrderByRegionAscEnergySourceAscPeriodTypeAscEffectiveFromDesc()
                .stream()
                .map(FeedInTariffDTO::from)
                .toList();
        return Result.ok(rows);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<FeedInTariffDTO>> create(@Valid @RequestBody CreateFeedInTariffReq req) {
        if (repo.existsByRegionAndEnergySourceAndPeriodTypeAndEffectiveFrom(
                req.region(), req.energySource(), req.periodType(), req.effectiveFrom())) {
            throw new ConflictException("已存在相同 (region, energy_source, period_type, effective_from) 的电价记录");
        }
        FeedInTariff saved = repo.save(new FeedInTariff(
                req.region(),
                req.energySource(),
                req.periodType(),
                req.effectiveFrom(),
                req.price()));
        return ResponseEntity.status(201).body(Result.ok(FeedInTariffDTO.from(saved)));
    }
}
