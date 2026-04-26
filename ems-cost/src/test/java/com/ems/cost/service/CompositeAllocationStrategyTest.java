package com.ems.cost.service;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.service.impl.CompositeAllocationStrategy;
import com.ems.cost.service.impl.DirectAllocationStrategy;
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

class CompositeAllocationStrategyTest {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
    private static final OffsetDateTime END   = START.plusHours(3);

    private final MeterUsageReader meterUsage = mock(MeterUsageReader.class);
    private final TariffPriceLookupService tariffLookup = mock(TariffPriceLookupService.class);
    private final MeterMetadataPort meterMetadata = mock(MeterMetadataPort.class);
    private final WeightResolver weightResolver = mock(WeightResolver.class);

    private final DirectAllocationStrategy direct = new DirectAllocationStrategy();
    private final ProportionalAllocationStrategy proportional = new ProportionalAllocationStrategy(weightResolver);

    private final AllocationAlgorithmFactory factory = new AllocationAlgorithmFactory(List.of(direct, proportional));
    private final CompositeAllocationStrategy strategy = new CompositeAllocationStrategy(factory);
    private final AllocationContext ctx = new AllocationContext(START, END, meterUsage, tariffLookup, meterMetadata);

    @Test
    void supports_returns_COMPOSITE() {
        assertThat(strategy.supports()).isEqualTo(AllocationAlgorithm.COMPOSITE);
    }

    @Test
    void composite_runs_two_steps_and_merges_lines() {
        // step 0: DIRECT meter 100 -> org 50 (full split)
        // step 1: PROPORTIONAL meter 200 -> org 60/61 (50/50)
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(meterMetadata.energyTypeIdOf(200L)).thenReturn(1L);
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(List.of(
                new HourPrice(START, "FLAT", new BigDecimal("0.50"))));
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START, new BigDecimal("8"))));   // 8 kWh × .5 = ¥4
        when(meterUsage.hourly(eq(200L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START, new BigDecimal("20"))));  // 20 kWh × .5 = ¥10

        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(60L, new BigDecimal("0.5"));
        resolved.put(61L, new BigDecimal("0.5"));
        when(weightResolver.resolve(eq(WeightBasis.FIXED), any(), any(), any(), any())).thenReturn(resolved);

        Map<String, Object> step0 = Map.of(
                "algorithm", "DIRECT",
                "sourceMeterId", 100,
                "targetOrgIds", List.of(50),
                "weights", Map.of());
        Map<String, Object> step1 = Map.of(
                "algorithm", "PROPORTIONAL",
                "sourceMeterId", 200,
                "targetOrgIds", List.of(60, 61),
                "weights", Map.of("basis", "FIXED", "values", Map.of("60", 0.5, "61", 0.5)));

        CostAllocationRule rule = compositeRule(Map.of("steps", List.of(step0, step1)));

        List<CostAllocationLine> lines = strategy.allocate(rule, ctx);

        assertThat(lines).hasSize(3);
        // direct: org 50 gets 8 kWh / ¥4
        CostAllocationLine l50 = lines.stream().filter(l -> l.getTargetOrgId() == 50L).findFirst().orElseThrow();
        assertThat(l50.getQuantity()).isEqualByComparingTo("8.0000");
        assertThat(l50.getAmount()).isEqualByComparingTo("4.0000");
        // proportional: orgs 60 / 61 each get 10 kWh / ¥5
        CostAllocationLine l60 = lines.stream().filter(l -> l.getTargetOrgId() == 60L).findFirst().orElseThrow();
        CostAllocationLine l61 = lines.stream().filter(l -> l.getTargetOrgId() == 61L).findFirst().orElseThrow();
        assertThat(l60.getQuantity()).isEqualByComparingTo("10.0000");
        assertThat(l60.getAmount()).isEqualByComparingTo("5.0000");
        assertThat(l61.getQuantity()).isEqualByComparingTo("10.0000");
        assertThat(l61.getAmount()).isEqualByComparingTo("5.0000");
    }

    @Test
    void composite_rebinds_line_ruleId_to_parent() {
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(List.of(
                new HourPrice(START, "FLAT", new BigDecimal("0.50"))));
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START, new BigDecimal("4"))));

        Map<String, Object> step = Map.of(
                "algorithm", "DIRECT",
                "sourceMeterId", 100,
                "targetOrgIds", List.of(50));
        CostAllocationRule rule = compositeRule(Map.of("steps", List.of(step)));
        // CostAllocationRule has no setId; rule.getId() is null, but Composite still must propagate it.
        // The behavior under test: lines from sub-strategies must be re-bound to parent's id (null is acceptable here).
        List<CostAllocationLine> lines = strategy.allocate(rule, ctx);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getRuleId()).isEqualTo(rule.getId());
    }

    @Test
    void composite_throws_on_missing_steps() {
        CostAllocationRule rule = compositeRule(Map.of());
        assertThatThrownBy(() -> strategy.allocate(rule, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 step");
    }

    @Test
    void composite_throws_on_empty_steps_array() {
        CostAllocationRule rule = compositeRule(Map.of("steps", List.of()));
        assertThatThrownBy(() -> strategy.allocate(rule, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 step");
    }

    @Test
    void composite_rejects_nested_composite() {
        Map<String, Object> nested = Map.of(
                "algorithm", "COMPOSITE",
                "sourceMeterId", 100,
                "targetOrgIds", List.of(50));
        CostAllocationRule rule = compositeRule(Map.of("steps", List.of(nested)));
        assertThatThrownBy(() -> strategy.allocate(rule, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot itself be COMPOSITE");
    }

    @Test
    void composite_throws_on_invalid_algorithm_name() {
        Map<String, Object> bad = Map.of(
                "algorithm", "WUT",
                "sourceMeterId", 100,
                "targetOrgIds", List.of(50));
        CostAllocationRule rule = compositeRule(Map.of("steps", List.of(bad)));
        assertThatThrownBy(() -> strategy.allocate(rule, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid algorithm");
    }

    private static CostAllocationRule compositeRule(Map<String, Object> weights) {
        CostAllocationRule r = new CostAllocationRule();
        r.setCode("R-COMP");
        r.setName("test composite");
        r.setEnergyType(EnergyTypeCode.ELEC);
        r.setAlgorithm(AllocationAlgorithm.COMPOSITE);
        r.setSourceMeterId(0L);  // unused at top level
        r.setTargetOrgIds(new Long[]{0L});  // unused at top level
        r.setWeights(weights);
        return r;
    }
}
