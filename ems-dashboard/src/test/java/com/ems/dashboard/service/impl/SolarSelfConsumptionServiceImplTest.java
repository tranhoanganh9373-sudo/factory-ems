package com.ems.dashboard.service.impl;

import com.ems.core.constant.ValueKind;
import com.ems.dashboard.dto.PvCurveDTO;
import com.ems.dashboard.service.SolarSelfConsumptionService.SelfConsumptionSummary;
import com.ems.dashboard.support.DashboardSupport;
import com.ems.dashboard.support.MeterRecord;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.MeterRole;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.TimeSeriesQueryService;
import com.ems.timeseries.query.TimeSeriesQueryService.MeterRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SolarSelfConsumptionServiceImplTest {

    @Mock private DashboardSupport support;
    @Mock private TimeSeriesQueryService tsq;

    private SolarSelfConsumptionServiceImpl service;
    private final TimeRange range = new TimeRange(
        Instant.parse("2026-05-04T00:00:00Z"),
        Instant.parse("2026-05-05T00:00:00Z"));

    @BeforeEach
    void setUp() { service = new SolarSelfConsumptionServiceImpl(support, tsq); }

    private MeterRecord m(Long id, MeterRole role, EnergySource source, FlowDirection dir) {
        return new MeterRecord(id, "M" + id, "Meter " + id, 1L, "tag" + id,
            1L, "ELEC", "kWh", true, ValueKind.INTERVAL_DELTA, role, source, dir);
    }

    // MeterPoint actual signature: (Long meterId, String meterCode, String energyTypeCode, List<TimePoint> points)
    private MeterPoint mp(Long meterId, List<TimePoint> points) {
        return new MeterPoint(meterId, "M" + meterId, "ELEC", points);
    }

    private TimePoint tp(String iso, double v) {
        return new TimePoint(Instant.parse(iso), v);
    }

    @Test
    void summarize_genGreaterThanLoad_clipsToZero() {
        var pv  = m(10L, MeterRole.GENERATE, EnergySource.SOLAR, FlowDirection.IMPORT);
        var exp = m(20L, MeterRole.GRID_TIE,  EnergySource.GRID,  FlowDirection.EXPORT);
        when(support.resolveMeters(any(), any())).thenReturn(List.of(pv, exp));
        when(tsq.queryByMeter(argThat(refs -> refs != null && refs.size() == 1
                && refs.iterator().next().meterId() == 10L), any(), any()))
            .thenReturn(List.of(mp(10L, List.of(
                tp("2026-05-04T11:00:00Z", 100.0),
                tp("2026-05-04T12:00:00Z", 50.0)))));
        when(tsq.queryByMeter(argThat(refs -> refs != null && refs.size() == 1
                && refs.iterator().next().meterId() == 20L), any(), any()))
            .thenReturn(List.of(mp(20L, List.of(
                tp("2026-05-04T11:00:00Z", 80.0),
                tp("2026-05-04T12:00:00Z", 0.0)))));

        SelfConsumptionSummary s = service.summarize(1L, range);

        // gen total=150, exp total=80, self=20+50=70, ratio=70/150≈0.4667
        assertThat(s.generation()).isEqualByComparingTo("150");
        assertThat(s.export()).isEqualByComparingTo("80");
        assertThat(s.selfConsumption()).isEqualByComparingTo("70");
        assertThat(s.selfRatio().doubleValue()).isCloseTo(0.4667, within(0.001));
    }

    @Test
    void summarize_genZero_returnsNullRatio() {
        var pv  = m(10L, MeterRole.GENERATE, EnergySource.SOLAR, FlowDirection.IMPORT);
        var exp = m(20L, MeterRole.GRID_TIE,  EnergySource.GRID,  FlowDirection.EXPORT);
        when(support.resolveMeters(any(), any())).thenReturn(List.of(pv, exp));
        when(tsq.queryByMeter(any(), any(), any())).thenReturn(List.of());

        SelfConsumptionSummary s = service.summarize(1L, range);

        assertThat(s.generation()).isEqualByComparingTo("0");
        assertThat(s.selfRatio()).isNull();
    }

    @Test
    void summarize_hourlyAggregateNotAveraged() {
        // CRITICAL invariant: 桶1: gen=100, exp=0 → self=100；桶2: gen=0, exp=100 → self=0
        // Naive sum-then-clip would give: gen=100, exp=100, self=max(0, 0)=0 (wrong)
        // Correct hourly-then-sum: 100 + 0 = 100
        var pv  = m(10L, MeterRole.GENERATE, EnergySource.SOLAR, FlowDirection.IMPORT);
        var exp = m(20L, MeterRole.GRID_TIE,  EnergySource.GRID,  FlowDirection.EXPORT);
        when(support.resolveMeters(any(), any())).thenReturn(List.of(pv, exp));
        when(tsq.queryByMeter(argThat(refs -> refs != null && refs.size() == 1
                && refs.iterator().next().meterId() == 10L), any(), any()))
            .thenReturn(List.of(mp(10L, List.of(
                tp("2026-05-04T11:00:00Z", 100.0),
                tp("2026-05-04T18:00:00Z", 0.0)))));
        when(tsq.queryByMeter(argThat(refs -> refs != null && refs.size() == 1
                && refs.iterator().next().meterId() == 20L), any(), any()))
            .thenReturn(List.of(mp(20L, List.of(
                tp("2026-05-04T11:00:00Z", 0.0),
                tp("2026-05-04T18:00:00Z", 100.0)))));

        SelfConsumptionSummary s = service.summarize(1L, range);

        assertThat(s.generation()).isEqualByComparingTo("100");
        assertThat(s.export()).isEqualByComparingTo("100");
        // KEY: per-hour sum, not aggregate-then-clip
        assertThat(s.selfConsumption()).isEqualByComparingTo("100");
    }

    @Test
    void curve_returnsHourlyBuckets() {
        var pv      = m(10L, MeterRole.GENERATE, EnergySource.SOLAR, FlowDirection.IMPORT);
        var consume = m(30L, MeterRole.CONSUME,  EnergySource.GRID,  FlowDirection.IMPORT);
        when(support.resolveMeters(any(), any())).thenReturn(List.of(pv, consume));
        when(tsq.queryByMeter(argThat(refs -> refs != null && refs.size() == 1
                && refs.iterator().next().meterId() == 10L), any(), any()))
            .thenReturn(List.of(mp(10L, List.of(tp("2026-05-04T11:00:00Z", 80.0)))));
        when(tsq.queryByMeter(argThat(refs -> refs != null && refs.size() == 1
                && refs.iterator().next().meterId() == 30L), any(), any()))
            .thenReturn(List.of(mp(30L, List.of(tp("2026-05-04T11:00:00Z", 50.0)))));

        PvCurveDTO out = service.curve(1L, range);

        assertThat(out.unit()).isEqualTo("kWh");
        assertThat(out.buckets()).hasSize(1);
        assertThat(out.buckets().get(0).generation()).isEqualTo(80.0);
        assertThat(out.buckets().get(0).load()).isEqualTo(50.0);
    }
}
