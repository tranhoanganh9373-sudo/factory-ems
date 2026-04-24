package com.ems.auth.controller;

import com.ems.auth.dto.RoleDTO;
import com.ems.auth.repository.RoleRepository;
import com.ems.core.dto.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {
    private final RoleRepository roles;
    public RoleController(RoleRepository r) { this.roles = r; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<RoleDTO>> list() {
        return Result.ok(roles.findAll().stream()
            .map(r -> new RoleDTO(r.getId(), r.getCode(), r.getName(), r.getDescription()))
            .toList());
    }
}
