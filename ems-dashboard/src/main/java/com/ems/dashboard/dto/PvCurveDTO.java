package com.ems.dashboard.dto;

import java.time.Instant;
import java.util.List;

public record PvCurveDTO(
    String unit,
    List<HourBucket> buckets
) {
    public record HourBucket(
        Instant ts,
        Double generation,
        Double load
    ) {}
}
