package com.ems.orgtree.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record OrgNodeDTO(
    Long id, Long parentId, String name, String code, String nodeType,
    Integer sortOrder, OffsetDateTime createdAt,
    List<OrgNodeDTO> children
) {}
