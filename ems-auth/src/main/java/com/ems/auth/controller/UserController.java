package com.ems.auth.controller;

import com.ems.auth.dto.*;
import com.ems.auth.security.AuthUser;
import com.ems.auth.service.UserService;
import com.ems.core.dto.PageDTO;
import com.ems.core.dto.Result;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService users;
    public UserController(UserService u) { this.users = u; }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageDTO<UserDTO>> list(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String keyword) {
        return Result.ok(users.list(page, size, keyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<UserDTO> get(@PathVariable Long id) { return Result.ok(users.getById(id)); }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<UserDTO>> create(@Valid @RequestBody CreateUserReq req) {
        return ResponseEntity.status(201).body(Result.ok(users.create(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<UserDTO> update(@PathVariable Long id, @Valid @RequestBody UpdateUserReq req) {
        return Result.ok(users.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        users.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody AssignRolesReq req) {
        users.assignRoles(id, req);
        return Result.ok();
    }

    @PutMapping("/{id}/password/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> reset(@PathVariable Long id, @Valid @RequestBody ResetPasswordReq req) {
        users.resetPassword(id, req.newPassword());
        return Result.ok();
    }

    @PutMapping("/me/password")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> changeOwn(@AuthenticationPrincipal AuthUser u,
                                  @Valid @RequestBody ChangePasswordReq req) {
        users.changePassword(u.getUserId(), req.oldPassword(), req.newPassword());
        return Result.ok();
    }
}
