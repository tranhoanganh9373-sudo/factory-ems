package com.ems.auth.dto;
import jakarta.validation.constraints.*;
public record CreateUserReq(
    @NotBlank @Size(min=3, max=64) @Pattern(regexp = "[A-Za-z0-9_.-]+") String username,
    @NotBlank @Size(min=8, max=64) String password,
    @Size(max=128) String displayName,
    java.util.List<String> roleCodes
) {}
