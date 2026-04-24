package com.ems.orgtree.dto;

import jakarta.validation.constraints.*;

public record CreateOrgNodeReq(
    Long parentId,
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_\\-]+") String code,
    @NotBlank @Size(max = 32) String nodeType,
    Integer sortOrder
) {}
