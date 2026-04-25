package com.ems.timeseries.rollup;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RollupAggregateTest {

    @Test
    void empty_isEmpty_andHasZeroSum() {
        RollupAggregate a = RollupAggregate.empty();
        assertThat(a.isEmpty()).isTrue();
        assertThat(a.count()).isZero();
        assertThat(a.sum()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void add_accumulatesSumMinMax() {
        RollupAggregate a = RollupAggregate.empty().add(2.0).add(5.0).add(1.0);
        assertThat(a.count()).isEqualTo(3);
        assertThat(a.sum()).isEqualByComparingTo(BigDecimal.valueOf(8.0));
        assertThat(a.max()).isEqualByComparingTo(BigDecimal.valueOf(5.0));
        assertThat(a.min()).isEqualByComparingTo(BigDecimal.valueOf(1.0));
    }

    @Test
    void avg_isSumOverCount() {
        RollupAggregate a = RollupAggregate.empty().add(1.0).add(2.0).add(3.0);
        assertThat(a.avg().doubleValue()).isCloseTo(2.0, within(0.0001));
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
