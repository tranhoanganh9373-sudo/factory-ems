package com.ems.orgtree.dto;

import jakarta.validation.constraints.*;

public record UpdateOrgNodeReq(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 32)  String nodeType,
    Integer sortOrder
) {}
