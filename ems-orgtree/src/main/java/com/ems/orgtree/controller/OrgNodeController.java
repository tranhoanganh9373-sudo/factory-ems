package com.ems.orgtree.controller;

import com.ems.core.dto.Result;
import com.ems.orgtree.dto.*;
import com.ems.orgtree.service.OrgNodeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/org-nodes")
public class OrgNodeController {

    private final OrgNodeService service;

    public OrgNodeController(OrgNodeService s) { this.service = s; }

    @GetMapping("/tree")
    @PreAuthorize("isAuthenticated()")
    public Result<List<OrgNodeDTO>> tree(@RequestParam(required = false) Long rootId) {
        return Result.ok(service.getTree(rootId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<OrgNodeDTO> get(@PathVariable Long id) { return Result.ok(service.getById(id)); }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<OrgNodeDTO>> create(@Valid @RequestBody CreateOrgNodeReq req) {
        OrgNodeDTO d = service.create(req);
        return ResponseEntity.status(201).body(Result.ok(d));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<OrgNodeDTO> update(@PathVariable Long id, @Valid @RequestBody UpdateOrgNodeReq req) {
        return Result.ok(service.update(id, req));
    }

    @PatchMapping("/{id}/move")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> move(@PathVariable Long id, @RequestBody MoveOrgNodeReq req) {
        service.move(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
