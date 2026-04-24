package com.ems.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ChangePasswordReq(@NotBlank String oldPassword,
                                @NotBlank @Size(min=8, max=64) String newPassword) {}
