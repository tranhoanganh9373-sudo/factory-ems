package com.ems.report.preset;

import com.ems.billing.dto.BillDTO;
import com.ems.billing.dto.BillPeriodDTO;
import com.ems.billing.entity.BillPeriodStatus;
import com.ems.billing.service.BillingService;
import com.ems.core.exception.BusinessException;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.orgtree.dto.OrgNodeDTO;
import com.ems.orgtree.service.OrgNodeService;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    BillingService billing;
    OrgNodeService orgNodes;
    ReportPresetServiceImpl svc;

    @BeforeEach
    void setup() {
        query = mock(ReportQueryService.class);
        shifts = mock(ShiftService.class);
        billing = mock(BillingService.class);
        orgNodes = mock(OrgNodeService.class);
        svc = new ReportPresetServiceImpl(query, shifts, billing, orgNodes);
        ReportMatrix stub = new ReportMatrix("stub",
                ReportMatrix.RowDimension.ORG_NODE, ReportMatrix.ColumnDimension.TIME_BUCKET,
                null, List.of(), List.of(), List.of(), 0.0);
        when(query.query(any(ReportMatrixRequest.class))).thenReturn(stub);
    }

    private BillDTO bill(Long orgId, EnergyTypeCode energy,
                         String sharp, String peak, String flat, String valley, String total) {
        return new BillDTO(
                100L, 1L, 7L, orgId, energy,
                BigDecimal.ZERO, new BigDecimal(total),
                new BigDecimal(sharp), new BigDecimal(peak),
                new BigDecimal(flat), new BigDecimal(valley),
                null, null, null,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private OrgNodeDTO orgDto(Long id, String name) {
        return new OrgNodeDTO(id, null, name, "ORG-" + id, "DEPT", 0, OffsetDateTime.now(ZoneOffset.UTC), List.of());
    }

    private BillPeriodDTO periodDto(Long id, String ym) {
        return new BillPeriodDTO(id, ym, BillPeriodStatus.CLOSED,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC),
                null, null, null, null,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
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

    // -------------------- costMonthly (Plan 2.2 Phase H) --------------------

    @Test
    void costMonthly_returns_empty_matrix_when_period_missing() {
        when(billing.getPeriodByYearMonth("2026-03")).thenThrow(new IllegalArgumentException("not found"));

        ReportMatrix m = svc.costMonthly(YearMonth.of(2026, 3), null);

        assertThat(m.rowDimension()).isEqualTo(ReportMatrix.RowDimension.COST_CENTER);
        assertThat(m.columnDimension()).isEqualTo(ReportMatrix.ColumnDimension.TARIFF_BAND);
        assertThat(m.unit()).isEqualTo("CNY");
        assertThat(m.columns()).hasSize(5);
        assertThat(m.rows()).isEmpty();
        assertThat(m.grandTotal()).isEqualTo(0.0);
    }

    @Test
    void costMonthly_filters_to_ELEC_only_and_aggregates_by_org() {
        when(billing.getPeriodByYearMonth("2026-03")).thenReturn(periodDto(100L, "2026-03"));
        when(billing.listBills(100L, null)).thenReturn(List.of(
                bill(50L, EnergyTypeCode.ELEC,  "100", "200", "300", "80", "680"),
                bill(50L, EnergyTypeCode.WATER, "0",   "0",   "0",   "0",  "120"),   // 应被过滤
                bill(51L, EnergyTypeCode.ELEC,  "60",  "120", "180", "40", "400")
        ));
        when(orgNodes.getById(50L)).thenReturn(orgDto(50L, "一车间"));
        when(orgNodes.getById(51L)).thenReturn(orgDto(51L, "二车间"));

        ReportMatrix m = svc.costMonthly(YearMonth.of(2026, 3), null);

        assertThat(m.rows()).hasSize(2);
        assertThat(m.rows().get(0).label()).isEqualTo("一车间");
        assertThat(m.rows().get(0).cells()).containsExactly(100.0, 200.0, 300.0, 80.0, 680.0);
        assertThat(m.rows().get(0).rowTotal()).isEqualTo(680.0);
        assertThat(m.rows().get(1).label()).isEqualTo("二车间");
        assertThat(m.columnTotals()).containsExactly(160.0, 320.0, 480.0, 120.0, 1080.0);
        assertThat(m.grandTotal()).isEqualTo(1080.0);
    }

    @Test
    void costMonthly_passes_orgNodeId_filter_to_billing_service() {
        when(billing.getPeriodByYearMonth("2026-03")).thenReturn(periodDto(100L, "2026-03"));
        when(billing.listBills(100L, 50L)).thenReturn(List.of(
                bill(50L, EnergyTypeCode.ELEC, "10", "20", "30", "5", "65")
        ));
        when(orgNodes.getById(50L)).thenReturn(orgDto(50L, "一车间"));

        ReportMatrix m = svc.costMonthly(YearMonth.of(2026, 3), 50L);

        assertThat(m.rows()).hasSize(1);
        assertThat(m.grandTotal()).isEqualTo(65.0);
    }

    @Test
    void costMonthly_falls_back_when_org_lookup_fails() {
        when(billing.getPeriodByYearMonth("2026-03")).thenReturn(periodDto(100L, "2026-03"));
        when(billing.listBills(100L, null)).thenReturn(List.of(
                bill(99L, EnergyTypeCode.ELEC, "10", "20", "30", "5", "65")
        ));
        when(orgNodes.getById(99L)).thenThrow(new RuntimeException("vanished"));

        ReportMatrix m = svc.costMonthly(YearMonth.of(2026, 3), null);

        assertThat(m.rows()).hasSize(1);
        assertThat(m.rows().get(0).label()).isEqualTo("Node 99");
    }

    @Test
    void costMonthly_nullYm_throws() {
        assertThatThrownBy(() -> svc.costMonthly(null, null))
                .isInstanceOf(BusinessException.class);
    }
}
