package com.ems.auth.dto;
import jakarta.validation.constraints.Size;
public record UpdateUserReq(
    @Size(max=128) String displayName,
    Boolean enabled
) {}
