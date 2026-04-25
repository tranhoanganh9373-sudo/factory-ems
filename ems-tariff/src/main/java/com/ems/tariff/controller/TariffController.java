package com.ems.tariff.controller;

import com.ems.core.dto.Result;
import com.ems.tariff.dto.*;
import com.ems.tariff.service.TariffService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tariff")
public class TariffController {

    private final TariffService service;

    public TariffController(TariffService service) { this.service = service; }

    @GetMapping("/plans")
    @PreAuthorize("isAuthenticated()")
    public Result<List<TariffPlanDTO>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/plans/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<TariffPlanDTO> getById(@PathVariable Long id) {
        return Result.ok(service.getById(id));
    }

    @PostMapping("/plans")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<TariffPlanDTO>> create(@Valid @RequestBody CreateTariffPlanReq req) {
        TariffPlanDTO dto = service.create(req);
        return ResponseEntity.status(201).body(Result.ok(dto));
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<TariffPlanDTO> update(@PathVariable Long id,
                                        @Valid @RequestBody UpdateTariffPlanReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/plans/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/resolve")
    @PreAuthorize("isAuthenticated()")
    public Result<ResolvedPriceDTO> resolve(
            @RequestParam Long energyTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
        return Result.ok(service.resolvePrice(energyTypeId, at));
    }
}
