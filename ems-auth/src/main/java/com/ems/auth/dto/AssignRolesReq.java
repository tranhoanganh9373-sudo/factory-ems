package com.ems.auth.dto;
import jakarta.validation.constraints.NotNull;
import java.util.List;
public record AssignRolesReq(@NotNull List<String> roleCodes) {}
