package com.ems.report.service;

import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.dashboard.support.DashboardSupport;
import com.ems.dashboard.support.MeterRecord;
import com.ems.report.dto.ReportRequest;
import com.ems.report.dto.ReportRow;
import com.ems.report.service.impl.ReportServiceImpl;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.TimeSeriesQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceImplTest {

    DashboardSupport support;
    TimeSeriesQueryService tsq;
    ReportServiceImpl svc;

    static final Instant T0 = Instant.parse("2026-04-25T00:00:00Z");
    static final Instant T1 = Instant.parse("2026-04-25T01:00:00Z");
    static final MeterRecord M1 = new MeterRecord(1L, "M-1", "Meter-1", 10L, "M-1", 1L, "ELEC", "kWh", true);
    static final MeterRecord M2 = new MeterRecord(2L, "M-2", "Meter-2", 11L, "M-2", 2L, "WATER", "m3", true);
    static final MeterRecord M3 = new MeterRecord(3L, "M-3", "Meter-3", 10L, "M-3", 1L, "ELEC", "kWh", true);

    @BeforeEach
    void setup() {
        support = mock(DashboardSupport.class);
        tsq = mock(TimeSeriesQueryService.class);
        svc = new ReportServiceImpl(support, tsq);
    }

    @Test
    void queryStream_flattensMeterPointsToRows_sortedByTsThenCode() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M1, M2));
        when(tsq.queryByMeter(anyCollection(), any(TimeRange.class), any(Granularity.class)))
            .thenReturn(List.of(
                new MeterPoint(1L, "M-1", "ELEC", List.of(new TimePoint(T0, 10.0), new TimePoint(T1, 20.0))),
                new MeterPoint(2L, "M-2", "WATER", List.of(new TimePoint(T0, 5.0), new TimePoint(T1, 7.0)))
            ));

        var req = new ReportRequest(T0, T1.plusSeconds(3600), Granularity.HOUR, null, null, null);
        List<ReportRow> rows = svc.queryStream(req).toList();

        assertThat(rows).hasSize(4);
        // T0: M-1 then M-2 (alpha)
        assertThat(rows.get(0).ts()).isEqualTo(T0);
        assertThat(rows.get(0).meterCode()).isEqualTo("M-1");
        assertThat(rows.get(0).value()).isEqualTo(10.0);
        assertThat(rows.get(1).ts()).isEqualTo(T0);
        assertThat(rows.get(1).meterCode()).isEqualTo("M-2");
        // T1
        assertThat(rows.get(2).ts()).isEqualTo(T1);
        assertThat(rows.get(2).meterCode()).isEqualTo("M-1");
        assertThat(rows.get(2).value()).isEqualTo(20.0);
        // 字段完整
        assertThat(rows.get(0).unit()).isEqualTo("kWh");
        assertThat(rows.get(0).orgNodeId()).isEqualTo(10L);
    }

    @Test
    void queryStream_emptyMeters_returnsEmpty_withoutHittingTsq() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of());

        var req = new ReportRequest(T0, T1, Granularity.HOUR, null, null, null);
        assertThat(svc.queryStream(req)).isEmpty();
        verify(tsq, never()).queryByMeter(anyCollection(), any(), any());
    }

    @Test
    void queryStream_filtersByEnergyType_caseInsensitive() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M1, M2, M3));
        // 假设过滤后只查 M1 + M3，但 mock 返回所有；svc 自己应当先过滤 visible
        when(tsq.queryByMeter(anyCollection(), any(), any())).thenAnswer(inv -> {
            var refs = inv.<java.util.Collection<TimeSeriesQueryService.MeterRef>>getArgument(0);
            return refs.stream()
                .map(r -> new MeterPoint(r.meterId(), null, r.energyTypeCode(),
                    List.of(new TimePoint(T0, 1.0))))
                .toList();
        });

        var req = new ReportRequest(T0, T1, Granularity.HOUR, null, List.of("elec"), null);
        var rows = svc.queryStream(req).toList();

        assertThat(rows).hasSize(2); // 仅 M1 + M3
        assertThat(rows).allMatch(r -> "ELEC".equals(r.energyTypeCode()));
    }

    @Test
    void queryStream_filtersByMeterIds() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M1, M2, M3));
        when(tsq.queryByMeter(anyCollection(), any(), any())).thenAnswer(inv -> {
            var refs = inv.<java.util.Collection<TimeSeriesQueryService.MeterRef>>getArgument(0);
            return refs.stream()
                .map(r -> new MeterPoint(r.meterId(), null, r.energyTypeCode(),
                    List.of(new TimePoint(T0, 1.0))))
                .toList();
        });

        var req = new ReportRequest(T0, T1, Granularity.HOUR, null, null, List.of(2L));
        var rows = svc.queryStream(req).toList();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).meterId()).isEqualTo(2L);
    }

    @Test
    void queryStream_invalidRequest_throwsParamInvalid() {
        // null req
        assertThatThrownBy(() -> svc.queryStream(null))
            .isInstanceOf(BusinessException.class)
            .extracting("code").isEqualTo(ErrorCode.PARAM_INVALID);
        // to <= from
        assertThatThrownBy(() -> svc.queryStream(new ReportRequest(T1, T0, Granularity.HOUR, null, null, null)))
            .isInstanceOf(BusinessException.class);
        // null granularity
        assertThatThrownBy(() -> svc.queryStream(new ReportRequest(T0, T1, null, null, null, null)))
            .isInstanceOf(BusinessException.class);
    }
}
