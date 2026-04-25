package com.ems.dashboard.dto;

import java.time.Instant;
import java.util.List;

public record MeterDetailDTO(
    Long meterId,
    String code,
    String name,
    String energyTypeCode,
    String unit,
    Long orgNodeId,
    Double total,
    List<Point> series
) {
    public record Point(Instant ts, Double value) {}
}
