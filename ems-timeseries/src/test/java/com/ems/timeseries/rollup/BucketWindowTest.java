package com.ems.timeseries.rollup;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BucketWindowTest {

    @Test
    void hourWindow_isOneHourWide() {
        Instant t = Instant.parse("2026-04-25T14:00:00Z");
        TimeRange r = BucketWindow.of(Granularity.HOUR, t);
        assertThat(r.start()).isEqualTo(t);
        assertThat(r.end()).isEqualTo(Instant.parse("2026-04-25T15:00:00Z"));
    }

    @Test
    void dayWindow_isOneDayWide() {
        Instant t = Instant.parse("2026-04-25T00:00:00Z");
        TimeRange r = BucketWindow.of(Granularity.DAY, t);
        assertThat(r.end()).isEqualTo(Instant.parse("2026-04-26T00:00:00Z"));
    }

    @Test
    void monthWindow_handlesVariableLength() {
        Instant t = Instant.parse("2026-04-01T00:00:00Z");
        assertThat(BucketWindow.of(Granularity.MONTH, t).end())
            .isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));

        Instant feb = Instant.parse("2026-02-01T00:00:00Z");
        assertThat(BucketWindow.of(Granularity.MONTH, feb).end())
            .isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    }

    @Test
    void truncate_alignsToBucketStart() {
        Instant t = Instant.parse("2026-04-25T14:37:42Z");
        assertThat(BucketWindow.truncate(Granularity.HOUR, t))
            .isEqualTo(Instant.parse("2026-04-25T14:00:00Z"));
        assertThat(BucketWindow.truncate(Granularity.DAY, t))
            .isEqualTo(Instant.parse("2026-04-25T00:00:00Z"));
        assertThat(BucketWindow.truncate(Granularity.MONTH, t))
            .isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
    }
}
