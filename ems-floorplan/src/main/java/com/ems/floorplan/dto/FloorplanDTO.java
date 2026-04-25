package com.ems.floorplan.dto;

import java.time.OffsetDateTime;

public record FloorplanDTO(
        Long id,
        String name,
        Long orgNodeId,
        String contentType,
        int widthPx,
        int heightPx,
        long fileSizeBytes,
        boolean enabled,
        OffsetDateTime createdAt
) {}
