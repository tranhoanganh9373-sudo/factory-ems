package com.ems.dashboard.dto;

public record TopNItemDTO(
    Long meterId,
    String code,
    String name,
    String energyTypeCode,
    String unit,
    Long orgNodeId,
    Double total
) {}
