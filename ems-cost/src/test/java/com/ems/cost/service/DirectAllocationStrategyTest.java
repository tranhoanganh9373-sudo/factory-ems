package com.ems.cost.service;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.service.impl.DirectAllocationStrategy;
import com.ems.tariff.service.HourPrice;
import com.ems.tariff.service.TariffPriceLookupService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DirectAllocationStrategyTest {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
    private static final OffsetDateTime END   = START.plusHours(3);

    private final MeterUsageReader meterUsage = mock(MeterUsageReader.class);
    private final TariffPriceLookupService tariffLookup = mock(TariffPriceLookupService.class);
    private final MeterMetadataPort meterMetadata = mock(MeterMetadataPort.class);

    private final DirectAllocationStrategy strategy = new DirectAllocationStrategy();
    private final AllocationContext ctx = new AllocationContext(START, END, meterUsage, tariffLookup, meterMetadata);

    private CostAllocationRule rule(Long sourceMeterId, Long... targetOrgIds) {
        CostAllocationRule r = new CostAllocationRule();
        r.setCode("R-DIRECT");
        r.setName("test direct");
        r.setEnergyType(EnergyTypeCode.ELEC);
        r.setAlgorithm(AllocationAlgorithm.DIRECT);
        r.setSourceMeterId(sourceMeterId);
        r.setTargetOrgIds(targetOrgIds);
        return r;
    }

    @Test
    void supports_returns_DIRECT() {
        assertThat(strategy.supports()).isEqualTo(AllocationAlgorithm.DIRECT);
    }

    @Test
    void direct_assigns_full_split_to_target_org_zero() {
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START,                new BigDecimal("10")),
                new MeterUsageReader.HourlyUsage(START.plusHours(1),   new BigDecimal("20")),
                new MeterUsageReader.HourlyUsage(START.plusHours(2),   new BigDecimal("30"))
        ));
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(List.of(
                new HourPrice(START,              "VALLEY", new BigDecimal("0.30")),
                new HourPrice(START.plusHours(1), "FLAT",   new BigDecimal("0.60")),
                new HourPrice(START.plusHours(2), "PEAK",   new BigDecimal("1.00"))
        ));

        List<CostAllocationLine> lines = strategy.allocate(rule(100L, 50L, 51L), ctx);

        assertThat(lines).hasSize(1);
        CostAllocationLine line = lines.get(0);
        assertThat(line.getTargetOrgId()).isEqualTo(50L);  // [0] only
        assertThat(line.getEnergyType()).isEqualTo(EnergyTypeCode.ELEC);
        assertThat(line.getValleyQuantity()).isEqualByComparingTo("10");
        assertThat(line.getValleyAmount()).isEqualByComparingTo("3.0000");
        assertThat(line.getFlatQuantity()).isEqualByComparingTo("20");
        assertThat(line.getFlatAmount()).isEqualByComparingTo("12.0000");
        assertThat(line.getPeakQuantity()).isEqualByComparingTo("30");
        assertThat(line.getPeakAmount()).isEqualByComparingTo("30.0000");
        assertThat(line.getQuantity()).isEqualByComparingTo("60.0000");
        assertThat(line.getAmount()).isEqualByComparingTo("45.0000");
    }

    @Test
    void direct_with_zero_usage_returns_zero_line() {
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(List.of());
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(List.of());

        List<CostAllocationLine> lines = strategy.allocate(rule(100L, 50L), ctx);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("0");
        assertThat(lines.get(0).getQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void direct_throws_on_empty_target_orgs() {
        CostAllocationRule r = rule(100L);
        assertThatThrownBy(() -> strategy.allocate(r, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 target_org_id");
    }
}
