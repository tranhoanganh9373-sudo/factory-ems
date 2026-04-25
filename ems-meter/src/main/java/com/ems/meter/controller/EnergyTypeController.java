package com.ems.meter.controller;

import com.ems.core.dto.Result;
import com.ems.meter.dto.EnergyTypeDTO;
import com.ems.meter.service.EnergyTypeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/energy-types")
public class EnergyTypeController {

    private final EnergyTypeService service;

    public EnergyTypeController(EnergyTypeService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<EnergyTypeDTO>> list() { return Result.ok(service.list()); }
}
