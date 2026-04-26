package com.ems.cost.service;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.service.impl.ResidualAllocationStrategy;
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

class ResidualAllocationStrategyTest {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
    private static final OffsetDateTime END   = START.plusHours(3);

    private final MeterUsageReader meterUsage = mock(MeterUsageReader.class);
    private final TariffPriceLookupService tariffLookup = mock(TariffPriceLookupService.class);
    private final MeterMetadataPort meterMetadata = mock(MeterMetadataPort.class);
    private final WeightResolver weightResolver = mock(WeightResolver.class);

    private final ResidualAllocationStrategy strategy = new ResidualAllocationStrategy(weightResolver);
    private final AllocationContext ctx = new AllocationContext(START, END, meterUsage, tariffLookup, meterMetadata);

    private static final List<HourPrice> PRICES_3H = List.of(
            new HourPrice(START,              "VALLEY", new BigDecimal("0.30")),
            new HourPrice(START.plusHours(1), "FLAT",   new BigDecimal("0.60")),
            new HourPrice(START.plusHours(2), "PEAK",   new BigDecimal("1.00")));

    private CostAllocationRule rule(Long sourceMeterId, Map<String, Object> weights, Long... targetOrgIds) {
        CostAllocationRule r = new CostAllocationRule();
        r.setCode("R-RES");
        r.setName("test residual");
        r.setEnergyType(EnergyTypeCode.ELEC);
        r.setAlgorithm(AllocationAlgorithm.RESIDUAL);
        r.setSourceMeterId(sourceMeterId);
        r.setTargetOrgIds(targetOrgIds);
        r.setWeights(weights);
        return r;
    }

    @Test
    void supports_returns_RESIDUAL() {
        assertThat(strategy.supports()).isEqualTo(AllocationAlgorithm.RESIDUAL);
    }

    @Test
    void residual_subtracts_child_meters_and_distributes() {
        // source: 10/20/30 → total 60kWh, ¥45 (V/F/P split: 10*.3=3, 20*.6=12, 30*1=30)
        // child 200: 5/10/10 → total 25kWh, ¥1.50+6+10=17.5
        // residual: 5+10+20 = 35 kWh, ¥27.5  (V=5*.3=1.5, F=10*.6=6, P=20*1=20)
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(PRICES_3H);
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START,              new BigDecimal("10")),
                new MeterUsageReader.HourlyUsage(START.plusHours(1), new BigDecimal("20")),
                new MeterUsageReader.HourlyUsage(START.plusHours(2), new BigDecimal("30"))));
        when(meterUsage.hourly(eq(200L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START,              new BigDecimal("5")),
                new MeterUsageReader.HourlyUsage(START.plusHours(1), new BigDecimal("10")),
                new MeterUsageReader.HourlyUsage(START.plusHours(2), new BigDecimal("10"))));

        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(50L, new BigDecimal("0.5"));
        resolved.put(51L, new BigDecimal("0.5"));
        when(weightResolver.resolve(eq(WeightBasis.FIXED), any(), any(), any(), any())).thenReturn(resolved);

        Map<String, Object> weights = Map.of(
                "deductMeterIds", List.of(200),
                "basis", "FIXED",
                "values", Map.of("50", 0.5, "51", 0.5));
        List<CostAllocationLine> lines = strategy.allocate(rule(100L, weights, 50L, 51L), ctx);

        assertThat(lines).hasSize(2);
        CostAllocationLine l50 = lines.stream().filter(l -> l.getTargetOrgId() == 50L).findFirst().orElseThrow();
        CostAllocationLine l51 = lines.stream().filter(l -> l.getTargetOrgId() == 51L).findFirst().orElseThrow();

        // each gets 50% of residual: 17.5 kWh, ¥13.75
        assertThat(l50.getQuantity()).isEqualByComparingTo("17.5000");
        assertThat(l50.getAmount()).isEqualByComparingTo("13.7500");
        assertThat(l51.getQuantity()).isEqualByComparingTo("17.5000");
        assertThat(l51.getAmount()).isEqualByComparingTo("13.7500");
        // sum reconstitutes residual
        assertThat(l50.getQuantity().add(l51.getQuantity())).isEqualByComparingTo("35.0000");
        assertThat(l50.getAmount().add(l51.getAmount())).isEqualByComparingTo("27.5000");
    }

    @Test
    void residual_multiple_deduct_meters_accumulate() {
        // source: 30/30/30 → 90kWh, V=9 F=18 P=30 total ¥57
        // child 200: 10/0/0 → 10kWh ¥3 V
        // child 201: 0/10/0 → 10kWh ¥6 F
        // child 202: 0/0/10 → 10kWh ¥10 P
        // residual: 20/20/20 = 60kWh, V=6 F=12 P=20 total ¥38
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(PRICES_3H);
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START,              new BigDecimal("30")),
                new MeterUsageReader.HourlyUsage(START.plusHours(1), new BigDecimal("30")),
                new MeterUsageReader.HourlyUsage(START.plusHours(2), new BigDecimal("30"))));
        when(meterUsage.hourly(eq(200L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START, new BigDecimal("10"))));
        when(meterUsage.hourly(eq(201L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START.plusHours(1), new BigDecimal("10"))));
        when(meterUsage.hourly(eq(202L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START.plusHours(2), new BigDecimal("10"))));

        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(50L, BigDecimal.ONE);
        when(weightResolver.resolve(any(), any(), any(), any(), any())).thenReturn(resolved);

        Map<String, Object> weights = Map.of(
                "deductMeterIds", List.of(200, 201, 202),
                "basis", "FIXED");
        List<CostAllocationLine> lines = strategy.allocate(rule(100L, weights, 50L), ctx);

        assertThat(lines).hasSize(1);
        CostAllocationLine l = lines.get(0);
        assertThat(l.getValleyQuantity()).isEqualByComparingTo("20.0000");
        assertThat(l.getFlatQuantity()).isEqualByComparingTo("20.0000");
        assertThat(l.getPeakQuantity()).isEqualByComparingTo("20.0000");
        assertThat(l.getValleyAmount()).isEqualByComparingTo("6.0000");
        assertThat(l.getFlatAmount()).isEqualByComparingTo("12.0000");
        assertThat(l.getPeakAmount()).isEqualByComparingTo("20.0000");
        assertThat(l.getQuantity()).isEqualByComparingTo("60.0000");
        assertThat(l.getAmount()).isEqualByComparingTo("38.0000");
    }

    @Test
    void residual_clamps_negative_to_zero_when_children_exceed_source() {
        // source: 10kWh in valley → ¥3
        // child 200: 25kWh in valley → ¥7.50
        // residual would be -15 / -¥4.50 → clamped to 0
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(List.of(
                new HourPrice(START, "VALLEY", new BigDecimal("0.30"))));
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START, new BigDecimal("10"))));
        when(meterUsage.hourly(eq(200L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START, new BigDecimal("25"))));

        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(50L, BigDecimal.ONE);
        when(weightResolver.resolve(any(), any(), any(), any(), any())).thenReturn(resolved);

        Map<String, Object> weights = Map.of(
                "deductMeterIds", List.of(200),
                "basis", "FIXED");
        List<CostAllocationLine> lines = strategy.allocate(rule(100L, weights, 50L), ctx);

        assertThat(lines).hasSize(1);
        // residual was negative → clamped to 0 → zero share
        assertThat(lines.get(0).getQuantity()).isEqualByComparingTo("0");
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("0");
        assertThat(lines.get(0).getValleyQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void residual_with_no_deduct_meters_passes_through_full_source() {
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(PRICES_3H);
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START,              new BigDecimal("10")),
                new MeterUsageReader.HourlyUsage(START.plusHours(1), new BigDecimal("20")),
                new MeterUsageReader.HourlyUsage(START.plusHours(2), new BigDecimal("30"))));

        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(50L, BigDecimal.ONE);
        when(weightResolver.resolve(any(), any(), any(), any(), any())).thenReturn(resolved);

        // no deductMeterIds key
        Map<String, Object> weights = Map.of("basis", "FIXED");
        List<CostAllocationLine> lines = strategy.allocate(rule(100L, weights, 50L), ctx);

        assertThat(lines).hasSize(1);
        // full 60kWh / ¥45 passes through to the single org
        assertThat(lines.get(0).getQuantity()).isEqualByComparingTo("60.0000");
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("45.0000");
    }

    @Test
    void residual_zero_residual_when_children_exactly_equal_source() {
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(PRICES_3H);
        List<MeterUsageReader.HourlyUsage> identical = List.of(
                new MeterUsageReader.HourlyUsage(START,              new BigDecimal("10")),
                new MeterUsageReader.HourlyUsage(START.plusHours(1), new BigDecimal("20")),
                new MeterUsageReader.HourlyUsage(START.plusHours(2), new BigDecimal("30")));
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(identical);
        when(meterUsage.hourly(eq(200L), any(), any())).thenReturn(identical);

        Map<Long, BigDecimal> resolved = new HashMap<>();
        resolved.put(50L, BigDecimal.ONE);
        when(weightResolver.resolve(any(), any(), any(), any(), any())).thenReturn(resolved);

        Map<String, Object> weights = Map.of("deductMeterIds", List.of(200), "basis", "FIXED");
        List<CostAllocationLine> lines = strategy.allocate(rule(100L, weights, 50L), ctx);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getQuantity()).isEqualByComparingTo("0");
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("0");
    }

    @Test
    void residual_throws_on_empty_target_orgs() {
        CostAllocationRule r = rule(100L, Map.of("basis", "FIXED"));
        assertThatThrownBy(() -> strategy.allocate(r, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 target_org_id");
    }
}
