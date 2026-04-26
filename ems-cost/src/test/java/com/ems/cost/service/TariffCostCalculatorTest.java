package com.ems.cost.service;

import com.ems.tariff.service.HourPrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TariffCostCalculatorTest {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final OffsetDateTime H0 = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);

    @Test
    void splitByTariff_assigns_each_hour_to_its_band() {
        // mix of all four bands at 4 hours
        List<MeterUsageReader.HourlyUsage> hourly = List.of(
                new MeterUsageReader.HourlyUsage(H0,              new BigDecimal("10")),
                new MeterUsageReader.HourlyUsage(H0.plusHours(1), new BigDecimal("20")),
                new MeterUsageReader.HourlyUsage(H0.plusHours(2), new BigDecimal("30")),
                new MeterUsageReader.HourlyUsage(H0.plusHours(3), new BigDecimal("40")));
        List<HourPrice> prices = List.of(
                new HourPrice(H0,              "VALLEY", new BigDecimal("0.30")),
                new HourPrice(H0.plusHours(1), "FLAT",   new BigDecimal("0.60")),
                new HourPrice(H0.plusHours(2), "PEAK",   new BigDecimal("1.00")),
                new HourPrice(H0.plusHours(3), "SHARP",  new BigDecimal("1.50")));

        TariffCostCalculator.Split s = TariffCostCalculator.splitByTariff(hourly, prices);

        assertThat(s.valleyQuantity()).isEqualByComparingTo("10.0000");
        assertThat(s.valleyAmount()).isEqualByComparingTo("3.0000");
        assertThat(s.flatQuantity()).isEqualByComparingTo("20.0000");
        assertThat(s.flatAmount()).isEqualByComparingTo("12.0000");
        assertThat(s.peakQuantity()).isEqualByComparingTo("30.0000");
        assertThat(s.peakAmount()).isEqualByComparingTo("30.0000");
        assertThat(s.sharpQuantity()).isEqualByComparingTo("40.0000");
        assertThat(s.sharpAmount()).isEqualByComparingTo("60.0000");
        assertThat(s.totalQuantity()).isEqualByComparingTo("100.0000");
        assertThat(s.totalAmount()).isEqualByComparingTo("105.0000");
    }

    @Test
    void splitByTariff_unmatched_hour_falls_back_to_FLAT_with_zero_amount() {
        List<MeterUsageReader.HourlyUsage> hourly = List.of(
                new MeterUsageReader.HourlyUsage(H0, new BigDecimal("10")));
        // no price entry for H0 → quantity goes to flat, amount = 0
        TariffCostCalculator.Split s = TariffCostCalculator.splitByTariff(hourly, List.of());

        assertThat(s.flatQuantity()).isEqualByComparingTo("10.0000");
        assertThat(s.flatAmount()).isEqualByComparingTo("0");
        assertThat(s.totalQuantity()).isEqualByComparingTo("10.0000");
        assertThat(s.totalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void splitByTariff_empty_inputs_returns_zero_split() {
        TariffCostCalculator.Split s = TariffCostCalculator.splitByTariff(List.of(), List.of());
        assertThat(s.totalQuantity()).isEqualByComparingTo("0");
        assertThat(s.totalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void flatOnly_puts_everything_in_flat_band() {
        TariffCostCalculator.Split s = TariffCostCalculator.flatOnly(new BigDecimal("100"), new BigDecimal("3.5"));
        assertThat(s.flatQuantity()).isEqualByComparingTo("100.0000");
        assertThat(s.flatAmount()).isEqualByComparingTo("350.0000");
        assertThat(s.sharpQuantity()).isEqualByComparingTo("0");
        assertThat(s.peakQuantity()).isEqualByComparingTo("0");
        assertThat(s.valleyQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void flatOnly_handles_null_inputs_as_zero() {
        TariffCostCalculator.Split s = TariffCostCalculator.flatOnly(null, null);
        assertThat(s.flatQuantity()).isEqualByComparingTo("0");
        assertThat(s.flatAmount()).isEqualByComparingTo("0");
    }

    @Test
    void scale_multiplies_each_band_by_weight() {
        TariffCostCalculator.Split src = new TariffCostCalculator.Split(
                new BigDecimal("10.0000"), new BigDecimal("20.0000"),
                new BigDecimal("30.0000"), new BigDecimal("40.0000"),
                new BigDecimal("1.0000"),  new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),  new BigDecimal("4.0000"));

        TariffCostCalculator.Split half = TariffCostCalculator.scale(src, new BigDecimal("0.5"));
        assertThat(half.sharpQuantity()).isEqualByComparingTo("5.0000");
        assertThat(half.peakQuantity()).isEqualByComparingTo("10.0000");
        assertThat(half.flatQuantity()).isEqualByComparingTo("15.0000");
        assertThat(half.valleyQuantity()).isEqualByComparingTo("20.0000");
        assertThat(half.totalAmount()).isEqualByComparingTo("5.0000");
    }

    @Test
    void scale_with_null_weight_returns_zero_split() {
        TariffCostCalculator.Split src = new TariffCostCalculator.Split(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        TariffCostCalculator.Split out = TariffCostCalculator.scale(src, null);
        assertThat(out.totalQuantity()).isEqualByComparingTo("0");
        assertThat(out.totalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void add_sums_two_splits_per_band() {
        TariffCostCalculator.Split a = new TariffCostCalculator.Split(
                new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"), new BigDecimal("4"),
                new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("0.3"), new BigDecimal("0.4"));
        TariffCostCalculator.Split b = new TariffCostCalculator.Split(
                new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("40"),
                new BigDecimal("1"),  new BigDecimal("2"),  new BigDecimal("3"),  new BigDecimal("4"));

        TariffCostCalculator.Split sum = TariffCostCalculator.add(a, b);
        assertThat(sum.sharpQuantity()).isEqualByComparingTo("11.0000");
        assertThat(sum.peakQuantity()).isEqualByComparingTo("22.0000");
        assertThat(sum.flatQuantity()).isEqualByComparingTo("33.0000");
        assertThat(sum.valleyQuantity()).isEqualByComparingTo("44.0000");
        assertThat(sum.totalAmount()).isEqualByComparingTo("11.0000");
    }

    @Test
    void zero_returns_all_zero_with_4dp_scale() {
        TariffCostCalculator.Split z = TariffCostCalculator.zero();
        assertThat(z.totalQuantity()).isEqualByComparingTo("0");
        assertThat(z.totalAmount()).isEqualByComparingTo("0");
        // ensure scale=4 (even though 0 == 0, ensure compatible types for ledger storage)
        assertThat(z.flatQuantity().scale()).isEqualTo(4);
    }

    @Test
    void subtract_positive_residual_does_not_flag_clamped() {
        TariffCostCalculator.Split src = new TariffCostCalculator.Split(
                new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"),
                new BigDecimal("1"),  new BigDecimal("1"),  new BigDecimal("1"),  new BigDecimal("1"));
        TariffCostCalculator.Split ded = new TariffCostCalculator.Split(
                new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("3"),
                new BigDecimal("0.3"), new BigDecimal("0.3"), new BigDecimal("0.3"), new BigDecimal("0.3"));

        TariffCostCalculator.SubtractResult r = TariffCostCalculator.subtract(src, ded);
        assertThat(r.clamped()).isFalse();
        assertThat(r.residual().sharpQuantity()).isEqualByComparingTo("7.0000");
        assertThat(r.residual().totalQuantity()).isEqualByComparingTo("28.0000");
        assertThat(r.residual().totalAmount()).isEqualByComparingTo("2.8000");
    }

    @Test
    void subtract_negative_residual_clamps_to_zero_and_sets_flag() {
        TariffCostCalculator.Split src = new TariffCostCalculator.Split(
                new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("3"),
                new BigDecimal("0.3"), new BigDecimal("0.3"), new BigDecimal("0.3"), new BigDecimal("0.3"));
        TariffCostCalculator.Split ded = new TariffCostCalculator.Split(
                new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"),
                new BigDecimal("1"),  new BigDecimal("1"),  new BigDecimal("1"),  new BigDecimal("1"));

        TariffCostCalculator.SubtractResult r = TariffCostCalculator.subtract(src, ded);
        assertThat(r.clamped()).isTrue();
        assertThat(r.residual().sharpQuantity()).isEqualByComparingTo("0");
        assertThat(r.residual().totalQuantity()).isEqualByComparingTo("0");
        assertThat(r.residual().totalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void splitByTariff_accumulates_multiple_hours_into_same_band() {
        // 3 hours all VALLEY: should accumulate, not overwrite
        List<MeterUsageReader.HourlyUsage> hourly = List.of(
                new MeterUsageReader.HourlyUsage(H0,              new BigDecimal("5")),
                new MeterUsageReader.HourlyUsage(H0.plusHours(1), new BigDecimal("7")),
                new MeterUsageReader.HourlyUsage(H0.plusHours(2), new BigDecimal("3")));
        List<HourPrice> prices = List.of(
                new HourPrice(H0,              "VALLEY", new BigDecimal("0.50")),
                new HourPrice(H0.plusHours(1), "VALLEY", new BigDecimal("0.50")),
                new HourPrice(H0.plusHours(2), "VALLEY", new BigDecimal("0.50")));

        TariffCostCalculator.Split s = TariffCostCalculator.splitByTariff(hourly, prices);
        assertThat(s.valleyQuantity()).isEqualByComparingTo("15.0000");
        assertThat(s.valleyAmount()).isEqualByComparingTo("7.5000");
        assertThat(s.flatQuantity()).isEqualByComparingTo("0");
    }
}
