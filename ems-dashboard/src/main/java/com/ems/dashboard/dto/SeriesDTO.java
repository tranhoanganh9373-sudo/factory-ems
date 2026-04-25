package com.ems.dashboard.dto;

import java.time.Instant;
import java.util.List;

/** 24h 实时曲线：按 energyType 分组，每组一条曲线。 */
public record SeriesDTO(
    String energyType,
    String unit,
    List<Bucket> points
) {
    public record Bucket(Instant ts, Double value) {}
}
