package com.ems.auth.controller;

import com.ems.auth.dto.*;
import com.ems.auth.service.NodePermissionService;
import com.ems.core.dto.Result;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users/{userId}/node-permissions")
@PreAuthorize("hasRole('ADMIN')")
public class NodePermissionController {

    private final NodePermissionService svc;
    public NodePermissionController(NodePermissionService s) { this.svc = s; }

    @GetMapping
    public Result<List<NodePermissionDTO>> list(@PathVariable Long userId) {
        return Result.ok(svc.listByUser(userId));
    }

    @PostMapping
    public ResponseEntity<Result<NodePermissionDTO>> assign(@PathVariable Long userId,
            @Valid @RequestBody AssignNodePermissionReq req) {
        return ResponseEntity.status(201).body(Result.ok(svc.assign(userId, req)));
    }

    @DeleteMapping("/{permissionId}")
    public ResponseEntity<Void> revoke(@PathVariable Long userId, @PathVariable Long permissionId) {
        svc.revoke(permissionId);
        return ResponseEntity.noContent().build();
    }
}
