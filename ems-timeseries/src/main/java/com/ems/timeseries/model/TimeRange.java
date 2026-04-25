package com.ems.timeseries.model;

import java.time.Instant;

public record TimeRange(Instant start, Instant end) {
    public TimeRange {
        if (start == null || end == null) throw new IllegalArgumentException("start/end 不能为 null");
        if (!end.isAfter(start)) throw new IllegalArgumentException("end 必须晚于 start");
    }
    public long durationSeconds() { return end.getEpochSecond() - start.getEpochSecond(); }
}
