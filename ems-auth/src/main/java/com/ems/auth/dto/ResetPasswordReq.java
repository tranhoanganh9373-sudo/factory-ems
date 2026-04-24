package com.ems.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ResetPasswordReq(@NotBlank @Size(min=8, max=64) String newPassword) {}
