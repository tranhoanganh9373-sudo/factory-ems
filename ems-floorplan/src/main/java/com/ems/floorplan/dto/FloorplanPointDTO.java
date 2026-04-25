package com.ems.floorplan.dto;

import java.math.BigDecimal;

public record FloorplanPointDTO(
        Long id,
        Long meterId,
        BigDecimal xRatio,
        BigDecimal yRatio,
        String label
) {}
