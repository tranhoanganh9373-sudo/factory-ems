package com.ems.report.carbon;

import com.ems.dashboard.service.SolarSelfConsumptionService;
import com.ems.meter.entity.CarbonFactor;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.repository.CarbonFactorRepository;
import com.ems.timeseries.model.TimeRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CarbonReportServiceImplTest {

    @Mock
    private SolarSelfConsumptionService selfConsumption;

    @Mock
    private CarbonFactorRepository carbonRepo;

    private CarbonReportServiceImpl svc;

    private static final TimeRange MARCH_2026 = new TimeRange(
        Instant.parse("2026-03-01T00:00:00Z"),
        Instant.parse("2026-04-01T00:00:00Z")
    );
    private static final LocalDate AS_OF = LocalDate.of(2026, 4, 1);
    private static final Long ORG_ID = 1L;

    @BeforeEach
    void setUp() {
        svc = new CarbonReportServiceImpl(selfConsumption, carbonRepo);
    }

    @Test
    void compute_typicalCase() {
        var summary = new SolarSelfConsumptionService.SelfConsumptionSummary(
            new BigDecimal("12000"),
            new BigDecimal("2000"),
            new BigDecimal("10000"),
            new BigDecimal("0.8333")
        );
        when(selfConsumption.summarize(ORG_ID, MARCH_2026)).thenReturn(summary);

        var gridCf = new CarbonFactor("CN", EnergySource.GRID, AS_OF, new BigDecimal("0.5810"));
        var solarCf = new CarbonFactor("CN", EnergySource.SOLAR, AS_OF, new BigDecimal("0.0480"));
        when(carbonRepo.findEffective("CN", EnergySource.GRID, AS_OF)).thenReturn(Optional.of(gridCf));
        when(carbonRepo.findEffective("CN", EnergySource.SOLAR, AS_OF)).thenReturn(Optional.of(solarCf));

        var result = svc.compute(ORG_ID, MARCH_2026);

        assertThat(result.selfConsumptionKwh()).isEqualByComparingTo("10000");
        assertThat(result.gridFactor()).isEqualByComparingTo("0.5810");
        assertThat(result.solarFactor()).isEqualByComparingTo("0.0480");
        assertThat(result.reductionKg()).isEqualByComparingTo("5330");
    }

    @Test
    void compute_noFactor_throws() {
        var summary = new SolarSelfConsumptionService.SelfConsumptionSummary(
            new BigDecimal("100"),
            BigDecimal.ZERO,
            new BigDecimal("100"),
            BigDecimal.ONE
        );
        when(selfConsumption.summarize(ORG_ID, MARCH_2026)).thenReturn(summary);
        when(carbonRepo.findEffective(eq("CN"), eq(EnergySource.GRID), any(LocalDate.class)))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.compute(ORG_ID, MARCH_2026))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No CarbonFactor");
    }
}
