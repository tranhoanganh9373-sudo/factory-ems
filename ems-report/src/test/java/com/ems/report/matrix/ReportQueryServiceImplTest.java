package com.ems.report.matrix;

import com.ems.core.constant.ValueKind;
import com.ems.core.exception.BusinessException;
import com.ems.dashboard.support.DashboardSupport;
import com.ems.dashboard.support.MeterRecord;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.MeterRole;
import com.ems.orgtree.dto.OrgNodeDTO;
import com.ems.orgtree.service.OrgNodeService;
import com.ems.report.matrix.impl.ReportQueryServiceImpl;
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
import static org.mockito.Mockito.when;

class ReportQueryServiceImplTest {

    DashboardSupport support;
    TimeSeriesQueryService tsq;
    OrgNodeService orgNodes;
    ReportQueryServiceImpl svc;

    static final MeterRecord M_ELEC_A = new MeterRecord(1L, "M-A", "A电表", 10L, "M-A", 1L, "ELEC", "kWh", true, ValueKind.INTERVAL_DELTA, MeterRole.CONSUME, EnergySource.GRID, FlowDirection.IMPORT);
    static final MeterRecord M_ELEC_B = new MeterRecord(2L, "M-B", "B电表", 11L, "M-B", 1L, "ELEC", "kWh", true, ValueKind.INTERVAL_DELTA, MeterRole.CONSUME, EnergySource.GRID, FlowDirection.IMPORT);
    static final MeterRecord M_WATER = new MeterRecord(3L, "M-W", "C水表", 10L, "M-W", 2L, "WATER", "m³", true, ValueKind.INTERVAL_DELTA, MeterRole.CONSUME, EnergySource.GRID, FlowDirection.IMPORT);

    @BeforeEach
    void setup() {
        support = mock(DashboardSupport.class);
        tsq = mock(TimeSeriesQueryService.class);
        orgNodes = mock(OrgNodeService.class);
        svc = new ReportQueryServiceImpl(support, tsq, orgNodes);
    }

    private static ReportMatrixRequest req(ReportMatrix.RowDimension rd, ReportMatrix.ColumnDimension cd, Granularity g) {
        return new ReportMatrixRequest(
                Instant.parse("2026-04-25T00:00:00Z"),
                Instant.parse("2026-04-27T00:00:00Z"),
                g, null, null, null, rd, cd, null
        );
    }

    @Test
    void query_pivotsByOrgAndTime_andComputesTotals() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M_ELEC_A, M_ELEC_B));
        when(orgNodes.getById(10L)).thenReturn(stubNode(10L, "一车间"));
        when(orgNodes.getById(11L)).thenReturn(stubNode(11L, "二车间"));

        Instant d1 = Instant.parse("2026-04-25T01:00:00Z");
        Instant d2 = Instant.parse("2026-04-26T01:00:00Z");
        when(tsq.queryByMeter(anyCollection(), any(TimeRange.class), any(Granularity.class))).thenReturn(List.of(
                new MeterPoint(1L, "M-A", "ELEC", List.of(new TimePoint(d1, 10.0), new TimePoint(d2, 20.0))),
                new MeterPoint(2L, "M-B", "ELEC", List.of(new TimePoint(d1, 5.0)))
        ));

        var m = svc.query(req(ReportMatrix.RowDimension.ORG_NODE,
                ReportMatrix.ColumnDimension.TIME_BUCKET, Granularity.DAY));

        assertThat(m.unit()).isEqualTo("kWh");
        assertThat(m.columns()).hasSize(2);
        assertThat(m.columns()).extracting(ReportMatrix.Column::key)
                .containsExactly("2026-04-25", "2026-04-26");
        assertThat(m.rows()).hasSize(2);

        var r10 = m.rows().stream().filter(r -> r.key().equals("10")).findFirst().orElseThrow();
        assertThat(r10.label()).isEqualTo("一车间");
        assertThat(r10.cells()).containsExactly(10.0, 20.0); // M-A only: d1=10, d2=20
        assertThat(r10.rowTotal()).isEqualTo(30.0);

        var r11 = m.rows().stream().filter(r -> r.key().equals("11")).findFirst().orElseThrow();
        assertThat(r11.label()).isEqualTo("二车间");
        assertThat(r11.cells()).containsExactly(5.0, 0.0); // M-B 在 d1 = 5
        assertThat(r11.rowTotal()).isEqualTo(5.0);

        assertThat(m.columnTotals()).containsExactly(15.0, 20.0);
        assertThat(m.grandTotal()).isEqualTo(35.0);
    }

    @Test
    void query_pivotsByOrgAndEnergyType() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M_ELEC_A, M_WATER));
        when(orgNodes.getById(10L)).thenReturn(stubNode(10L, "一车间"));

        Instant t = Instant.parse("2026-04-25T01:00:00Z");
        when(tsq.queryByMeter(anyCollection(), any(), any())).thenReturn(List.of(
                new MeterPoint(1L, "M-A", "ELEC", List.of(new TimePoint(t, 100.0))),
                new MeterPoint(3L, "M-W", "WATER", List.of(new TimePoint(t, 50.0)))
        ));

        var m = svc.query(req(ReportMatrix.RowDimension.ORG_NODE,
                ReportMatrix.ColumnDimension.ENERGY_TYPE, Granularity.DAY));

        assertThat(m.unit()).isNull();  // mixed unit
        assertThat(m.columns()).extracting(ReportMatrix.Column::key)
                .containsExactly("ELEC", "WATER"); // sorted
        assertThat(m.rows()).singleElement().satisfies(r -> {
            assertThat(r.cells()).containsExactly(100.0, 50.0);
            assertThat(r.rowTotal()).isEqualTo(150.0);
        });
        assertThat(m.columnTotals()).containsExactly(100.0, 50.0);
        assertThat(m.grandTotal()).isEqualTo(150.0);
    }

    @Test
    void query_pivotsByMeter_andSortsByLabel() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M_ELEC_A, M_ELEC_B));
        Instant t = Instant.parse("2026-04-25T01:00:00Z");
        when(tsq.queryByMeter(anyCollection(), any(), any())).thenReturn(List.of(
                new MeterPoint(2L, "M-B", "ELEC", List.of(new TimePoint(t, 7.0))),
                new MeterPoint(1L, "M-A", "ELEC", List.of(new TimePoint(t, 3.0)))
        ));

        var m = svc.query(req(ReportMatrix.RowDimension.METER,
                ReportMatrix.ColumnDimension.TIME_BUCKET, Granularity.DAY));

        assertThat(m.rows()).extracting(ReportMatrix.Row::key)
                .containsExactly("1", "2");  // M-A < M-B 字典序
    }

    @Test
    void query_emptyMeters_returnsEmptyMatrix() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of());
        var m = svc.query(req(ReportMatrix.RowDimension.ORG_NODE,
                ReportMatrix.ColumnDimension.TIME_BUCKET, Granularity.DAY));
        assertThat(m.rows()).isEmpty();
        assertThat(m.columns()).isEmpty();
        assertThat(m.grandTotal()).isEqualTo(0.0);
    }

    @Test
    void query_validatesFromBeforeTo() {
        var bad = new ReportMatrixRequest(
                Instant.parse("2026-04-27T00:00:00Z"),
                Instant.parse("2026-04-25T00:00:00Z"),
                Granularity.DAY, null, null, null,
                ReportMatrix.RowDimension.ORG_NODE,
                ReportMatrix.ColumnDimension.TIME_BUCKET, null);
        assertThatThrownBy(() -> svc.query(bad)).isInstanceOf(BusinessException.class);
    }

    @Test
    void query_filtersByExplicitMeterIds() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M_ELEC_A, M_ELEC_B, M_WATER));
        Instant t = Instant.parse("2026-04-25T01:00:00Z");
        when(tsq.queryByMeter(anyCollection(), any(), any())).thenReturn(List.of(
                new MeterPoint(1L, "M-A", "ELEC", List.of(new TimePoint(t, 10.0)))
        ));
        when(orgNodes.getById(10L)).thenReturn(stubNode(10L, "一车间"));

        var req = new ReportMatrixRequest(
                Instant.parse("2026-04-25T00:00:00Z"),
                Instant.parse("2026-04-26T00:00:00Z"),
                Granularity.DAY, null, null, List.of(1L),
                ReportMatrix.RowDimension.METER,
                ReportMatrix.ColumnDimension.TIME_BUCKET, "测试");
        var m = svc.query(req);

        assertThat(m.title()).isEqualTo("测试");
        assertThat(m.rows()).singleElement().satisfies(r -> assertThat(r.key()).isEqualTo("1"));
    }

    private static OrgNodeDTO stubNode(Long id, String name) {
        return new OrgNodeDTO(id, null, name, "C-" + id, "WORKSHOP", 0, null, List.of());
    }
}
