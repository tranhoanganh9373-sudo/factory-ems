package com.ems.tariff.service;

import com.ems.tariff.entity.TariffPeriod;
import com.ems.tariff.entity.TariffPlan;
import com.ems.tariff.repository.TariffPeriodRepository;
import com.ems.tariff.repository.TariffPlanRepository;
import com.ems.tariff.service.impl.TariffPriceLookupServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TariffPriceLookupServiceTest {

    private static final Long ENERGY_ID = 1L;
    private static final ZoneOffset Z = ZoneOffset.ofHours(8);

    private final TariffPlanRepository plans = mock(TariffPlanRepository.class);
    private final TariffPeriodRepository periods = mock(TariffPeriodRepository.class);
    private final TariffPriceLookupService svc = new TariffPriceLookupServiceImpl(plans, periods);

    private TariffPlan plan(long id) {
        TariffPlan p = new TariffPlan();
        // reflection workaround — TariffPlan id has no setter; for MVP test we use a stub that returns id
        try {
            var f = TariffPlan.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (Exception e) { throw new RuntimeException(e); }
        return p;
    }

    private TariffPeriod period(String type, LocalTime start, LocalTime end, String price) {
        TariffPeriod p = new TariffPeriod();
        p.setPeriodType(type);
        p.setTimeStart(start);
        p.setTimeEnd(end);
        p.setPricePerUnit(new BigDecimal(price));
        return p;
    }

    @Test
    void empty_window_returns_empty() {
        OffsetDateTime t = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
        assertThat(svc.batch(ENERGY_ID, t, t)).isEmpty();
        assertThat(svc.batch(ENERGY_ID, t.plusHours(1), t)).isEmpty();
    }

    @Test
    void four_band_day_assigns_correct_period_to_each_hour() {
        when(plans.findFirstActiveByEnergyTypeId(eq(ENERGY_ID), any()))
                .thenReturn(Optional.of(plan(10L)));
        when(periods.findByPlanIdOrderByTimeStartAsc(10L)).thenReturn(List.of(
                period("VALLEY", LocalTime.of(0, 0), LocalTime.of(8, 0),  "0.30"),
                period("FLAT",   LocalTime.of(8, 0), LocalTime.of(10, 0), "0.60"),
                period("PEAK",   LocalTime.of(10, 0), LocalTime.of(15, 0), "1.00"),
                period("FLAT",   LocalTime.of(15, 0), LocalTime.of(18, 0), "0.60"),
                period("SHARP",  LocalTime.of(18, 0), LocalTime.of(21, 0), "1.50"),
                period("FLAT",   LocalTime.of(21, 0), LocalTime.of(0, 0),  "0.60")
        ));

        OffsetDateTime start = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
        OffsetDateTime end   = start.plusDays(1);
        List<HourPrice> rows = svc.batch(ENERGY_ID, start, end);

        assertThat(rows).hasSize(24);
        assertThat(rows.get(0).periodType()).isEqualTo("VALLEY");
        assertThat(rows.get(0).pricePerUnit()).isEqualByComparingTo("0.30");
        assertThat(rows.get(7).periodType()).isEqualTo("VALLEY");
        assertThat(rows.get(8).periodType()).isEqualTo("FLAT");
        assertThat(rows.get(10).periodType()).isEqualTo("PEAK");
        assertThat(rows.get(14).periodType()).isEqualTo("PEAK");
        assertThat(rows.get(15).periodType()).isEqualTo("FLAT");
        assertThat(rows.get(18).periodType()).isEqualTo("SHARP");
        assertThat(rows.get(20).periodType()).isEqualTo("SHARP");
        assertThat(rows.get(21).periodType()).isEqualTo("FLAT");
        assertThat(rows.get(23).periodType()).isEqualTo("FLAT");
    }

    @Test
    void cross_midnight_period_handled() {
        when(plans.findFirstActiveByEnergyTypeId(eq(ENERGY_ID), any()))
                .thenReturn(Optional.of(plan(11L)));
        when(periods.findByPlanIdOrderByTimeStartAsc(11L)).thenReturn(List.of(
                period("VALLEY", LocalTime.of(22, 0), LocalTime.of(6, 0),  "0.20"),
                period("FLAT",   LocalTime.of(6, 0),  LocalTime.of(22, 0), "0.50")
        ));

        OffsetDateTime start = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
        List<HourPrice> rows = svc.batch(ENERGY_ID, start, start.plusDays(1));

        // 0..5  → VALLEY, 6..21 → FLAT, 22..23 → VALLEY
        assertThat(rows.get(0).periodType()).isEqualTo("VALLEY");
        assertThat(rows.get(5).periodType()).isEqualTo("VALLEY");
        assertThat(rows.get(6).periodType()).isEqualTo("FLAT");
        assertThat(rows.get(21).periodType()).isEqualTo("FLAT");
        assertThat(rows.get(22).periodType()).isEqualTo("VALLEY");
        assertThat(rows.get(23).periodType()).isEqualTo("VALLEY");
    }

    @Test
    void no_active_plan_falls_back_to_flat_zero() {
        when(plans.findFirstActiveByEnergyTypeId(eq(ENERGY_ID), any()))
                .thenReturn(Optional.empty());

        OffsetDateTime start = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
        List<HourPrice> rows = svc.batch(ENERGY_ID, start, start.plusHours(3));

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.periodType()).isEqualTo("FLAT");
            assertThat(r.pricePerUnit()).isEqualByComparingTo("0");
        });
    }

    @Test
    void non_aligned_start_floors_up_to_next_hour() {
        when(plans.findFirstActiveByEnergyTypeId(eq(ENERGY_ID), any()))
                .thenReturn(Optional.of(plan(12L)));
        when(periods.findByPlanIdOrderByTimeStartAsc(12L)).thenReturn(List.of(
                period("FLAT", LocalTime.of(0, 0), LocalTime.of(0, 0), "0.50") // wraps full day
        ));

        OffsetDateTime start = OffsetDateTime.of(2026, 3, 1, 9, 30, 0, 0, Z);
        OffsetDateTime end   = OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, Z);
        // ceil(start) = 10:00; range 10..11 inclusive → 2 hours
        List<HourPrice> rows = svc.batch(ENERGY_ID, start, end);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).hourStart().getHour()).isEqualTo(10);
        assertThat(rows.get(1).hourStart().getHour()).isEqualTo(11);
    }
}
