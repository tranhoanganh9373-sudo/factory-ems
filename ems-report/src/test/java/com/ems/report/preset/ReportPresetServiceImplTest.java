package com.ems.report.preset;

import com.ems.core.exception.BusinessException;
import com.ems.production.dto.ShiftDTO;
import com.ems.production.service.ShiftService;
import com.ems.report.matrix.ReportMatrix;
import com.ems.report.matrix.ReportMatrixRequest;
import com.ems.report.matrix.ReportQueryService;
import com.ems.report.preset.impl.ReportPresetServiceImpl;
import com.ems.timeseries.model.Granularity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportPresetServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    ReportQueryService query;
    ShiftService shifts;
    ReportPresetServiceImpl svc;

    @BeforeEach
    void setup() {
        query = mock(ReportQueryService.class);
        shifts = mock(ShiftService.class);
        svc = new ReportPresetServiceImpl(query, shifts);
        ReportMatrix stub = new ReportMatrix("stub",
                ReportMatrix.RowDimension.ORG_NODE, ReportMatrix.ColumnDimension.TIME_BUCKET,
                null, List.of(), List.of(), List.of(), 0.0);
        when(query.query(any(ReportMatrixRequest.class))).thenReturn(stub);
    }

    private ReportMatrixRequest captureRequest() {
        ArgumentCaptor<ReportMatrixRequest> cap = ArgumentCaptor.forClass(ReportMatrixRequest.class);
        verify(query).query(cap.capture());
        return cap.getValue();
    }

    @Test
    void daily_buildsHourlyRangeOverFullDay() {
        svc.daily(LocalDate.parse("2026-04-25"), 10L, List.of("ELEC"));
        var r = captureRequest();
        assertThat(r.granularity()).isEqualTo(Granularity.HOUR);
        assertThat(r.rowDimension()).isEqualTo(ReportMatrix.RowDimension.ORG_NODE);
        assertThat(r.columnDimension()).isEqualTo(ReportMatrix.ColumnDimension.TIME_BUCKET);
        assertThat(r.from()).isEqualTo(LocalDate.parse("2026-04-25").atStartOfDay(ZONE).toInstant());
        assertThat(r.to()).isEqualTo(LocalDate.parse("2026-04-26").atStartOfDay(ZONE).toInstant());
        assertThat(r.orgNodeId()).isEqualTo(10L);
        assertThat(r.energyTypes()).containsExactly("ELEC");
        assertThat(r.title()).isEqualTo("日报 2026-04-25");
    }

    @Test
    void monthly_buildsDayRangeOverFullMonth() {
        svc.monthly(YearMonth.parse("2026-04"), 10L, null);
        var r = captureRequest();
        assertThat(r.granularity()).isEqualTo(Granularity.DAY);
        assertThat(r.from()).isEqualTo(LocalDate.parse("2026-04-01").atStartOfDay(ZONE).toInstant());
        assertThat(r.to()).isEqualTo(LocalDate.parse("2026-05-01").atStartOfDay(ZONE).toInstant());
        assertThat(r.title()).isEqualTo("月报 2026-04");
    }

    @Test
    void yearly_buildsMonthRangeOverFullYear() {
        svc.yearly(Year.of(2026), null, null);
        var r = captureRequest();
        assertThat(r.granularity()).isEqualTo(Granularity.MONTH);
        assertThat(r.from()).isEqualTo(LocalDate.parse("2026-01-01").atStartOfDay(ZONE).toInstant());
        assertThat(r.to()).isEqualTo(LocalDate.parse("2027-01-01").atStartOfDay(ZONE).toInstant());
        assertThat(r.orgNodeId()).isNull();
        assertThat(r.title()).isEqualTo("年报 2026");
    }

    @Test
    void shift_sameDay_buildsHourlyRangeWithEnergyTypeColumns() {
        ShiftDTO morning = new ShiftDTO(7L, "M", "早班",
                LocalTime.of(8, 0), LocalTime.of(16, 0), true, 0);
        when(shifts.getById(7L)).thenReturn(morning);

        svc.shift(LocalDate.parse("2026-04-25"), 7L, 10L, null);
        var r = captureRequest();

        assertThat(r.columnDimension()).isEqualTo(ReportMatrix.ColumnDimension.ENERGY_TYPE);
        assertThat(r.from()).isEqualTo(
                LocalDate.parse("2026-04-25").atTime(8, 0).atZone(ZONE).toInstant());
        assertThat(r.to()).isEqualTo(
                LocalDate.parse("2026-04-25").atTime(16, 0).atZone(ZONE).toInstant());
        assertThat(r.title()).isEqualTo("班次报表 2026-04-25 M");
    }

    @Test
    void shift_crossMidnight_extendsToNextDay() {
        ShiftDTO night = new ShiftDTO(9L, "N", "夜班",
                LocalTime.of(22, 0), LocalTime.of(6, 0), true, 2);
        when(shifts.getById(9L)).thenReturn(night);

        svc.shift(LocalDate.parse("2026-04-25"), 9L, null, null);
        var r = captureRequest();

        // 22:00 当日 → 06:00 次日
        assertThat(r.from()).isEqualTo(
                LocalDate.parse("2026-04-25").atTime(22, 0).atZone(ZONE).toInstant());
        assertThat(r.to()).isEqualTo(
                LocalDate.parse("2026-04-26").atTime(6, 0).atZone(ZONE).toInstant());
        // 时长检查：8h
        assertThat(java.time.Duration.between(r.from(), r.to()).toHours()).isEqualTo(8);
    }

    @Test
    void daily_nullDate_throws() {
        assertThatThrownBy(() -> svc.daily(null, 10L, null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void shift_nullShiftId_throws() {
        assertThatThrownBy(() -> svc.shift(LocalDate.parse("2026-04-25"), null, null, null))
                .isInstanceOf(BusinessException.class);
    }
}
