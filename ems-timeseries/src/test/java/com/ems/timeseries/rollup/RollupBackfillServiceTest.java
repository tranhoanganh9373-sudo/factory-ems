package com.ems.timeseries.rollup;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.rollup.RollupComputeService.MeterCtx;
import com.ems.timeseries.rollup.dto.BackfillReq;
import com.ems.timeseries.rollup.dto.BackfillResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollupBackfillServiceTest {

    RollupComputeService compute;
    MeterCatalogPort meters;
    RollupBackfillService backfill;

    @BeforeEach
    void setup() {
        compute = mock(RollupComputeService.class);
        meters = mock(MeterCatalogPort.class);
        backfill = new RollupBackfillService(compute, meters);
        when(compute.computeBucket(any(), any(), any())).thenReturn(true);
    }

    @Test
    void rebuild_iteratesEveryBucketForEveryMeter() {
        MeterCtx m1 = new MeterCtx(1L, 10L, "M1");
        MeterCtx m2 = new MeterCtx(2L, 10L, "M2");
        when(meters.findAllEnabled()).thenReturn(List.of(m1, m2));

        BackfillReq req = new BackfillReq(
            Granularity.HOUR,
            Instant.parse("2026-04-25T00:00:00Z"),
            Instant.parse("2026-04-25T03:00:00Z"),
            null);

        BackfillResult r = backfill.rebuild(req);

        // 4 桶（00, 01, 02, 03） × 2 meters = 8 调用
        verify(compute, times(8)).computeBucket(any(), eq(Granularity.HOUR), any());
        assertThat(r.meters()).isEqualTo(2);
        assertThat(r.buckets()).isEqualTo(4);
        assertThat(r.ok()).isEqualTo(8);
        assertThat(r.failed()).isZero();
    }

    @Test
    void rebuild_specificMeterIds_resolvesViaPort() {
        when(meters.findById(7L)).thenReturn(Optional.of(new MeterCtx(7L, 10L, "M7")));
        when(meters.findById(99L)).thenReturn(Optional.empty()); // 99 不存在 → 跳过

        BackfillReq req = new BackfillReq(
            Granularity.DAY,
            Instant.parse("2026-04-24T00:00:00Z"),
            Instant.parse("2026-04-25T00:00:00Z"),
            List.of(7L, 99L));

        BackfillResult r = backfill.rebuild(req);
        assertThat(r.meters()).isEqualTo(1);   // 99 被丢弃
        verify(compute, times(2)).computeBucket(any(), eq(Granularity.DAY), any()); // 24, 25
    }

    @Test
    void rebuild_minute_rejected() {
        BackfillReq req = new BackfillReq(Granularity.MINUTE,
            Instant.parse("2026-04-25T00:00:00Z"),
            Instant.parse("2026-04-25T01:00:00Z"), null);
        assertThatThrownBy(() -> backfill.rebuild(req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MINUTE");
    }

    @Test
    void rebuild_invalidRange_rejected() {
        BackfillReq req = new BackfillReq(Granularity.HOUR,
            Instant.parse("2026-04-25T01:00:00Z"),
            Instant.parse("2026-04-25T01:00:00Z"), null);  // to == from
        assertThatThrownBy(() -> backfill.rebuild(req))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
