package com.ems.meter.controller;

import com.ems.core.dto.Result;
import com.ems.meter.dto.CreateMeterReq;
import com.ems.meter.dto.MeterDTO;
import com.ems.meter.dto.UpdateMeterReq;
import com.ems.meter.service.MeterService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/meters")
public class MeterController {

    private final MeterService service;

    public MeterController(MeterService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<MeterDTO>> list(
            @RequestParam(required = false) Long orgNodeId,
            @RequestParam(required = false) Long energyTypeId,
            @RequestParam(required = false) Boolean enabled) {
        return Result.ok(service.list(orgNodeId, energyTypeId, enabled));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<MeterDTO> get(@PathVariable Long id) { return Result.ok(service.getById(id)); }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<MeterDTO>> create(@Valid @RequestBody CreateMeterReq req) {
        MeterDTO d = service.create(req);
        return ResponseEntity.status(201).body(Result.ok(d));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<MeterDTO> update(@PathVariable Long id, @Valid @RequestBody UpdateMeterReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
