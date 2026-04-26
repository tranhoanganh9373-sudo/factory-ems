package com.ems.cost.service;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.service.impl.ProportionalAllocationStrategy;
import com.ems.tariff.service.HourPrice;
import com.ems.tariff.service.TariffPriceLookupService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProportionalAllocationStrategyTest {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
    private static final OffsetDateTime END   = START.plusHours(3);

    private final MeterUsageReader meterUsage = mock(MeterUsageReader.class);
    private final TariffPriceLookupService tariffLookup = mock(TariffPriceLookupService.class);
    private final MeterMetadataPort meterMetadata = mock(MeterMetadataPort.class);
    private final WeightResolver weightResolver = mock(WeightResolver.class);

    private final ProportionalAllocationStrategy strategy = new ProportionalAllocationStrategy(weightResolver);
    private final AllocationContext ctx = new AllocationContext(START, END, meterUsage, tariffLookup, meterMetadata);

    private CostAllocationRule ruleFixed(Long sourceMeterId, Map<String, Object> weights, Long... targetOrgIds) {
        CostAllocationRule r = new CostAllocationRule();
        r.setCode("R-PROP");
        r.setName("test proportional");
        r.setEnergyType(EnergyTypeCode.ELEC);
        r.setAlgorithm(AllocationAlgorithm.PROPORTIONAL);
        r.setSourceMeterId(sourceMeterId);
        r.setTargetOrgIds(targetOrgIds);
        r.setWeights(weights);
        return r;
    }

    private void wireFullSplit3h() {
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START,              new BigDecimal("10")),
                new MeterUsageReader.HourlyUsage(START.plusHours(1), new BigDecimal("20")),
                new MeterUsageReader.HourlyUsage(START.plusHours(2), new BigDecimal("30"))
        ));
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(List.of(
                new HourPrice(START,              "VALLEY", new BigDecimal("0.30")),
                new HourPrice(START.plusHours(1), "FLAT",   new BigDecimal("0.60")),
                new HourPrice(START.plusHours(2), "PEAK",   new BigDecimal("1.00"))
        ));
    }

    @Test
    void supports_returns_PROPORTIONAL() {
        assertThat(strategy.supports()).isEqualTo(AllocationAlgorithm.PROPORTIONAL);
    }

    @Test
    void proportional_fixed_two_orgs_60_40() {
        wireFullSplit3h();
        // total: 60 kWh / ¥45 → 60% / 40% split
        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(50L, new BigDecimal("0.6"));
        resolved.put(51L, new BigDecimal("0.4"));
        when(weightResolver.resolve(eq(WeightBasis.FIXED), any(), any(), any(), any())).thenReturn(resolved);

        Map<String, Object> weights = Map.of(
                "basis", "FIXED",
                "values", Map.of("50", 0.6, "51", 0.4));

        List<CostAllocationLine> lines = strategy.allocate(ruleFixed(100L, weights, 50L, 51L), ctx);

        assertThat(lines).hasSize(2);

        CostAllocationLine l50 = lines.stream().filter(l -> l.getTargetOrgId() == 50L).findFirst().orElseThrow();
        CostAllocationLine l51 = lines.stream().filter(l -> l.getTargetOrgId() == 51L).findFirst().orElseThrow();

        // 60% of 60kWh = 36kWh, 60% of ¥45 = ¥27
        assertThat(l50.getQuantity()).isEqualByComparingTo("36.0000");
        assertThat(l50.getAmount()).isEqualByComparingTo("27.0000");
        // 40% of 60kWh = 24kWh, 40% of ¥45 = ¥18
        assertThat(l51.getQuantity()).isEqualByComparingTo("24.0000");
        assertThat(l51.getAmount()).isEqualByComparingTo("18.0000");
        // sum should reconstitute the source
        assertThat(l50.getQuantity().add(l51.getQuantity())).isEqualByComparingTo("60.0000");
        assertThat(l50.getAmount().add(l51.getAmount())).isEqualByComparingTo("45.0000");
    }

    @Test
    void proportional_fixed_per_band_scaling_preserves_components() {
        wireFullSplit3h();
        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(50L, new BigDecimal("1.0"));
        when(weightResolver.resolve(any(), any(), any(), any(), any())).thenReturn(resolved);

        Map<String, Object> weights = Map.of("basis", "FIXED", "values", Map.of("50", 1.0));
        List<CostAllocationLine> lines = strategy.allocate(ruleFixed(100L, weights, 50L), ctx);

        assertThat(lines).hasSize(1);
        CostAllocationLine l = lines.get(0);
        // weight=1 means full pass-through of the 4-band split
        assertThat(l.getValleyQuantity()).isEqualByComparingTo("10.0000");
        assertThat(l.getValleyAmount()).isEqualByComparingTo("3.0000");
        assertThat(l.getFlatQuantity()).isEqualByComparingTo("20.0000");
        assertThat(l.getFlatAmount()).isEqualByComparingTo("12.0000");
        assertThat(l.getPeakQuantity()).isEqualByComparingTo("30.0000");
        assertThat(l.getPeakAmount()).isEqualByComparingTo("30.0000");
        assertThat(l.getSharpQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void proportional_zero_weight_org_gets_zero_line() {
        wireFullSplit3h();
        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(50L, new BigDecimal("1.0"));
        // 51L missing → defaults to ZERO inside the strategy
        when(weightResolver.resolve(any(), any(), any(), any(), any())).thenReturn(resolved);

        Map<String, Object> weights = Map.of("basis", "FIXED", "values", Map.of("50", 1.0));
        List<CostAllocationLine> lines = strategy.allocate(ruleFixed(100L, weights, 50L, 51L), ctx);

        assertThat(lines).hasSize(2);
        CostAllocationLine l51 = lines.stream().filter(l -> l.getTargetOrgId() == 51L).findFirst().orElseThrow();
        assertThat(l51.getQuantity()).isEqualByComparingTo("0");
        assertThat(l51.getAmount()).isEqualByComparingTo("0");
    }

    @Test
    void proportional_throws_on_empty_target_orgs() {
        CostAllocationRule r = ruleFixed(100L, Map.of("basis", "FIXED"));
        assertThatThrownBy(() -> strategy.allocate(r, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 target_org_id");
    }

    @Test
    void proportional_basis_defaults_to_FIXED_on_null_weights() {
        wireFullSplit3h();
        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(50L, BigDecimal.ONE);
        when(weightResolver.resolve(eq(WeightBasis.FIXED), any(), any(), any(), any())).thenReturn(resolved);

        CostAllocationRule r = ruleFixed(100L, null, 50L);
        List<CostAllocationLine> lines = strategy.allocate(r, ctx);

        assertThat(lines).hasSize(1);
        // fact that we got here means basis defaulted to FIXED (matched the eq() stub)
        assertThat(lines.get(0).getQuantity()).isEqualByComparingTo("60.0000");
    }

    @Test
    void proportional_basis_AREA_routed_to_resolver() {
        wireFullSplit3h();
        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(50L, new BigDecimal("0.5"));
        resolved.put(51L, new BigDecimal("0.5"));
        when(weightResolver.resolve(eq(WeightBasis.AREA), any(), any(), any(), any())).thenReturn(resolved);

        Map<String, Object> weights = Map.of("basis", "AREA");
        List<CostAllocationLine> lines = strategy.allocate(ruleFixed(100L, weights, 50L, 51L), ctx);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getQuantity()).isEqualByComparingTo("30.0000");
        assertThat(lines.get(1).getQuantity()).isEqualByComparingTo("30.0000");
    }

    @Test
    void proportional_unknown_basis_defaults_to_FIXED() {
        wireFullSplit3h();
        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(50L, BigDecimal.ONE);
        when(weightResolver.resolve(eq(WeightBasis.FIXED), any(), any(), any(), any())).thenReturn(resolved);

        Map<String, Object> weights = Map.of("basis", "GARBAGE_VALUE");
        List<CostAllocationLine> lines = strategy.allocate(ruleFixed(100L, weights, 50L), ctx);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getQuantity()).isEqualByComparingTo("60.0000");
    }
}
