package com.ems.meter.controller;

import com.ems.core.dto.Result;
import com.ems.meter.dto.BindParentMeterReq;
import com.ems.meter.dto.MeterTopologyEdgeDTO;
import com.ems.meter.service.MeterTopologyService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/meter-topology")
public class MeterTopologyController {

    private final MeterTopologyService service;

    public MeterTopologyController(MeterTopologyService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<MeterTopologyEdgeDTO>> list() { return Result.ok(service.listAll()); }

    @PutMapping("/{childMeterId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> bind(@PathVariable Long childMeterId, @Valid @RequestBody BindParentMeterReq req) {
        service.bind(childMeterId, req);
        return Result.ok();
    }

    @DeleteMapping("/{childMeterId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> unbind(@PathVariable Long childMeterId) {
        service.unbind(childMeterId);
        return Result.ok();
    }
}
