package com.ems.auth.dto;
import jakarta.validation.constraints.*;
public record AssignNodePermissionReq(
    @NotNull Long orgNodeId,
    @NotNull @Pattern(regexp = "SUBTREE|NODE_ONLY") String scope
) {}
