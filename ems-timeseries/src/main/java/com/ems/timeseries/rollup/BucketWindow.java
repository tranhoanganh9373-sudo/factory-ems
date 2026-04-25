package com.ems.timeseries.rollup;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.TimeRange;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 给定粒度 + 桶起点 → 该桶的 [start, end) 时间区间。所有桶按 UTC 对齐。
 */
public final class BucketWindow {

    private BucketWindow() {}

    public static TimeRange of(Granularity granularity, Instant bucketStart) {
        return switch (granularity) {
            case HOUR  -> new TimeRange(bucketStart, bucketStart.plus(1, ChronoUnit.HOURS));
            case DAY   -> new TimeRange(bucketStart, bucketStart.plus(1, ChronoUnit.DAYS));
            case MONTH -> {
                YearMonth ym = YearMonth.from(bucketStart.atOffset(ZoneOffset.UTC));
                Instant end = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                yield new TimeRange(bucketStart, end);
            }
            case MINUTE -> new TimeRange(bucketStart, bucketStart.plus(1, ChronoUnit.MINUTES));
        };
    }

    /** 对齐到桶起点（截断）。 */
    public static Instant truncate(Granularity granularity, Instant t) {
        return switch (granularity) {
            case HOUR  -> t.truncatedTo(ChronoUnit.HOURS);
            case DAY   -> t.truncatedTo(ChronoUnit.DAYS);
            case MONTH -> {
                LocalDate d = t.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
                yield d.atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            case MINUTE -> t.truncatedTo(ChronoUnit.MINUTES);
        };
    }
}
