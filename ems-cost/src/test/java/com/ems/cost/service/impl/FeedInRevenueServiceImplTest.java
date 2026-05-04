package com.ems.cost.service.impl;

import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FeedInTariff;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.FeedInTariffRepository;
import com.ems.meter.repository.MeterRepository;
import com.ems.cost.service.MeterUsageReader;
import com.ems.cost.service.MeterUsageReader.HourlyUsage;
import com.ems.tariff.service.TariffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedInRevenueServiceImplTest {

    @Mock
    private FeedInTariffRepository tariffRepo;
    @Mock
    private MeterRepository meterRepository;
    @Mock
    private MeterUsageReader usageReader;
    @Mock
    private TariffService tariffService;

    private FeedInRevenueServiceImpl service;

    private static final Long ORG_ID = 1L;
    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
    private static final LocalDate TO   = LocalDate.of(2025, 1, 31);
    private static final ZoneOffset TZ  = ZoneOffset.ofHours(8);

    @BeforeEach
    void setUp() {
        service = new FeedInRevenueServiceImpl(tariffRepo, meterRepository, usageReader, tariffService);
    }

    // ------------------------------------------------------------------
    // Helper builders
    // ------------------------------------------------------------------

    private Meter exportMeter(Long id, EnergySource source) {
        Meter m = new Meter();
        m.setId(id);
        m.setCode("M-" + id);
        m.setName("Export Meter " + id);
        m.setEnergyTypeId(10L);
        m.setOrgNodeId(ORG_ID);
        m.setInfluxMeasurement("electricity");
        m.setInfluxTagKey("meter");
        m.setInfluxTagValue("M-" + id);
        m.setFlowDirection(FlowDirection.EXPORT);
        m.setEnergySource(source);
        return m;
    }

    private FeedInTariff tariff(String period, String price) {
        return new FeedInTariff("CN", EnergySource.SOLAR, period,
                LocalDate.of(2024, 1, 1), new BigDecimal(price));
    }

    // ------------------------------------------------------------------
    // Test 1: multi-period TOU tariff sums correctly
    // 100 kWh PEAK × 0.45 + 200 kWh FLAT × 0.40 + 50 kWh VALLEY × 0.35
    // = 45 + 80 + 17.5 = 142.5
    // ------------------------------------------------------------------
    @Test
    void multiPeriodTariff_sumsCorrectly() {
        Meter m = exportMeter(100L, EnergySource.SOLAR);
        when(meterRepository.findByOrgNodeIdIn(List.of(ORG_ID))).thenReturn(List.of(m));

        // 10 PEAK hours × 10 kWh = 100 kWh (day 1, hours 0-9)
        // 20 FLAT hours × 10 kWh = 200 kWh (day 1-2, hours 10-29 mod 24)
        // 5  VALLEY hours × 10 kWh = 50 kWh (day 3, hours 0-4)
        List<HourlyUsage> usages = new ArrayList<>();
        for (int h = 0; h < 10; h++) {
            usages.add(new HourlyUsage(
                OffsetDateTime.of(2025, 1, 1, h, 0, 0, 0, TZ),
                new BigDecimal("10")));
        }
        for (int h = 10; h < 30; h++) {
            int day = (h / 24) + 1;
            int hourOfDay = h % 24;
            usages.add(new HourlyUsage(
                OffsetDateTime.of(2025, 1, day, hourOfDay, 0, 0, 0, TZ),
                new BigDecimal("10")));
        }
        for (int i = 0; i < 5; i++) {
            usages.add(new HourlyUsage(
                OffsetDateTime.of(2025, 1, 3, i, 0, 0, 0, TZ),
                new BigDecimal("10")));
        }

        when(usageReader.hourly(eq(100L), any(), any())).thenReturn(usages);

        when(tariffService.resolvePeriodType(eq(10L), any(OffsetDateTime.class)))
            .thenAnswer(inv -> {
                OffsetDateTime ts = inv.getArgument(1);
                int day = ts.getDayOfMonth();
                int h   = ts.getHour();
                if (day == 1 && h < 10) return "PEAK";
                if (day == 3 && h < 5)  return "VALLEY";
                return "FLAT";
            });

        when(tariffRepo.findEffective("CN", EnergySource.SOLAR, "PEAK",   TO))
            .thenReturn(Optional.of(tariff("PEAK",   "0.45")));
        when(tariffRepo.findEffective("CN", EnergySource.SOLAR, "FLAT",   TO))
            .thenReturn(Optional.of(tariff("FLAT",   "0.40")));
        when(tariffRepo.findEffective("CN", EnergySource.SOLAR, "VALLEY", TO))
            .thenReturn(Optional.of(tariff("VALLEY", "0.35")));

        BigDecimal revenue = service.computeRevenue(ORG_ID, EnergySource.SOLAR, FROM, TO);

        // 100*0.45 + 200*0.40 + 50*0.35 = 45 + 80 + 17.5 = 142.5
        assertThat(revenue).isEqualByComparingTo("142.5");
    }

    // ------------------------------------------------------------------
    // Test 2: no tariff row for a period that has export → IllegalStateException
    // ------------------------------------------------------------------
    @Test
    void noTariffRow_throwsIllegalStateException() {
        Meter m = exportMeter(101L, EnergySource.SOLAR);
        when(meterRepository.findByOrgNodeIdIn(List.of(ORG_ID))).thenReturn(List.of(m));

        List<HourlyUsage> usages = List.of(
            new HourlyUsage(
                OffsetDateTime.of(2025, 1, 1, 12, 0, 0, 0, TZ),
                new BigDecimal("50"))
        );
        when(usageReader.hourly(eq(101L), any(), any())).thenReturn(usages);
        when(tariffService.resolvePeriodType(eq(10L), any(OffsetDateTime.class))).thenReturn("FLAT");
        when(tariffRepo.findEffective(eq("CN"), eq(EnergySource.SOLAR), eq("FLAT"), any(LocalDate.class)))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            service.computeRevenue(ORG_ID, EnergySource.SOLAR, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No FeedInTariff");
    }

    // ------------------------------------------------------------------
    // Test 3: zero export (no EXPORT meters) → returns ZERO, no tariff lookup
    // ------------------------------------------------------------------
    @Test
    void zeroExport_returnsZero() {
        Meter importMeter = exportMeter(102L, EnergySource.SOLAR);
        importMeter.setFlowDirection(FlowDirection.IMPORT);
        when(meterRepository.findByOrgNodeIdIn(List.of(ORG_ID))).thenReturn(List.of(importMeter));

        BigDecimal revenue = service.computeRevenue(ORG_ID, EnergySource.SOLAR, FROM, TO);

        assertThat(revenue).isEqualByComparingTo(BigDecimal.ZERO);
        verifyNoInteractions(usageReader, tariffRepo);
    }
}
