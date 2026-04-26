package com.ems.cost.controller;

import com.ems.core.dto.Result;
import com.ems.cost.dto.CostRuleDTO;
import com.ems.cost.dto.CreateCostRuleReq;
import com.ems.cost.dto.UpdateCostRuleReq;
import com.ems.cost.service.CostRuleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Cost rule REST CRUD.
 * Reads: any authenticated user (FINANCE / ADMIN / OPS need to inspect rules).
 * Writes: FINANCE or ADMIN — only finance owns the cost-allocation policy.
 */
@RestController
@RequestMapping("/api/v1/cost/rules")
public class CostRuleController {

    private final CostRuleService service;

    public CostRuleController(CostRuleService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<CostRuleDTO>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<CostRuleDTO> getById(@PathVariable Long id) {
        return Result.ok(service.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<Result<CostRuleDTO>> create(@Valid @RequestBody CreateCostRuleReq req) {
        CostRuleDTO dto = service.create(req);
        return ResponseEntity.status(201).body(Result.ok(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public Result<CostRuleDTO> update(@PathVariable Long id,
                                      @Valid @RequestBody UpdateCostRuleReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
