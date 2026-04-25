package com.ems.report.controller;

import com.ems.report.matrix.ReportMatrix;
import com.ems.report.preset.ReportPresetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportPresetControllerTest {

    ReportPresetService presets;
    ReportPresetController controller;

    private static ReportMatrix sample() {
        return new ReportMatrix(
                "日报 2026-04-25",
                ReportMatrix.RowDimension.ORG_NODE,
                ReportMatrix.ColumnDimension.TIME_BUCKET,
                "kWh",
                List.of(new ReportMatrix.Column("h0", "00:00"),
                        new ReportMatrix.Column("h1", "01:00")),
                List.of(new ReportMatrix.Row("10", "一车间", List.of(10.0, 20.0), 30.0),
                        new ReportMatrix.Row("11", "二车间", List.of(5.0, 0.0), 5.0)),
                List.of(15.0, 20.0),
                35.0
        );
    }

    @BeforeEach
    void setup() {
        presets = mock(ReportPresetService.class);
        controller = new ReportPresetController(presets);
    }

    @Test
    void daily_delegatesAndAdaptsMatrix() {
        when(presets.daily(any(LocalDate.class), any(), any())).thenReturn(sample());
        var v = controller.daily(LocalDate.parse("2026-04-25"), 10L, List.of("ELEC"));

        verify(presets).daily(LocalDate.parse("2026-04-25"), 10L, List.of("ELEC"));
        assertThat(v.title()).isEqualTo("日报 2026-04-25");
        assertThat(v.rowAxis()).isEqualTo("组织节点");
        assertThat(v.colAxis()).isEqualTo("时段");
        assertThat(v.unit()).isEqualTo("kWh");
        assertThat(v.rowLabels()).containsExactly("一车间", "二车间");
        assertThat(v.colLabels()).containsExactly("00:00", "01:00");
        assertThat(v.values().get(0)).containsExactly(10.0, 20.0);
        assertThat(v.rowTotals()).containsExactly(30.0, 5.0);
        assertThat(v.columnTotals()).containsExactly(15.0, 20.0);
        assertThat(v.grandTotal()).isEqualTo(35.0);
    }

    @Test
    void monthly_parsesYearMonth() {
        when(presets.monthly(any(YearMonth.class), any(), any())).thenReturn(sample());
        var v = controller.monthly("2026-04", null, null);
        verify(presets).monthly(YearMonth.parse("2026-04"), null, null);
        assertThat(v.title()).contains("日报");
    }

    @Test
    void yearly_acceptsIntYear() {
        when(presets.yearly(any(Year.class), any(), any())).thenReturn(sample());
        controller.yearly(2026, null, null);
        verify(presets).yearly(Year.of(2026), null, null);
    }

    @Test
    void shift_delegatesWithBothDateAndShiftId() {
        when(presets.shift(any(LocalDate.class), anyLong(), any(), anyList())).thenReturn(sample());
        controller.shift(LocalDate.parse("2026-04-25"), 7L, 10L, List.of("ELEC"));
        verify(presets).shift(LocalDate.parse("2026-04-25"), 7L, 10L, List.of("ELEC"));
    }

    @Test
    void energyTypeAxis_mapsToEnergyTypeLabel() {
        ReportMatrix m = new ReportMatrix("班次", ReportMatrix.RowDimension.METER,
                ReportMatrix.ColumnDimension.ENERGY_TYPE, "kWh",
                List.of(new ReportMatrix.Column("ELEC", "电力")),
                List.of(new ReportMatrix.Row("M1", "M1", List.of(1.0), 1.0)),
                List.of(1.0), 1.0);
        when(presets.shift(any(), anyLong(), any(), any())).thenReturn(m);
        var v = controller.shift(LocalDate.parse("2026-04-25"), 7L, null, null);
        assertThat(v.rowAxis()).isEqualTo("测点");
        assertThat(v.colAxis()).isEqualTo("能源品类");
    }
}
