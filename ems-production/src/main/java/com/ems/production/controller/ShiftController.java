package com.ems.production.controller;

import com.ems.core.dto.Result;
import com.ems.production.dto.CreateShiftReq;
import com.ems.production.dto.ShiftDTO;
import com.ems.production.dto.UpdateShiftReq;
import com.ems.production.service.ShiftService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shifts")
public class ShiftController {

    private final ShiftService service;

    public ShiftController(ShiftService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<ShiftDTO>> list(@RequestParam(defaultValue = "true") boolean enabledOnly) {
        return Result.ok(service.list(enabledOnly));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<ShiftDTO> getById(@PathVariable Long id) {
        return Result.ok(service.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<ShiftDTO>> create(@Valid @RequestBody CreateShiftReq req) {
        ShiftDTO dto = service.create(req);
        return ResponseEntity.status(201).body(Result.ok(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<ShiftDTO> update(@PathVariable Long id, @Valid @RequestBody UpdateShiftReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
