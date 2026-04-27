package com.ems.collector.poller;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AccumulatorTrackerTest {

    @Test
    void firstObservation_returnsNull() {
        AccumulatorTracker t = new AccumulatorTracker();
        assertThat(t.observe("energy", new BigDecimal("100.0"), BigDecimal.ONE)).isNull();
    }

    @Test
    void monotonicallyIncreasing_returnsDifference() {
        AccumulatorTracker t = new AccumulatorTracker();
        t.observe("energy", new BigDecimal("100.00"), BigDecimal.ONE);
        BigDecimal d = t.observe("energy", new BigDecimal("103.50"), BigDecimal.ONE);
        assertThat(d).isEqualByComparingTo("3.50");
    }

    @Test
    void multipleSteadyIncrements() {
        AccumulatorTracker t = new AccumulatorTracker();
        t.observe("energy", new BigDecimal("100.0"), BigDecimal.ONE);
        assertThat(t.observe("energy", new BigDecimal("110.0"), BigDecimal.ONE))
                .isEqualByComparingTo("10.0");
        assertThat(t.observe("energy", new BigDecimal("120.0"), BigDecimal.ONE))
                .isEqualByComparingTo("10.0");
        assertThat(t.observe("energy", new BigDecimal("125.5"), BigDecimal.ONE))
                .isEqualByComparingTo("5.5");
    }

    @Test
    void uint32WrapAround_withScaleOne() {
        // 仿真：仪表上 UINT32 max-1 → 翻转后 = 5
        // prev raw = 0xFFFFFFFE = 4294967294
        // curr raw = 5
        // expected raw delta = (0xFFFFFFFF - 4294967294) + 5 + 1 = 1 + 5 + 1 = 7
        AccumulatorTracker t = new AccumulatorTracker();
        t.observe("energy", new BigDecimal("4294967294"), BigDecimal.ONE);
        BigDecimal delta = t.observe("energy", new BigDecimal("5"), BigDecimal.ONE);
        assertThat(delta).isEqualByComparingTo("7");
    }

    @Test
    void uint32WrapAround_withScaleSmallerThanOne() {
        // raw 仪表用 0.01 kWh 单位（scale=0.01）；UINT32 满刻度 = 0xFFFFFFFF × 0.01 = 42949672.95 kWh
        // 假设 prev (raw)=4294967290 → scaled = 42949672.90
        // curr (raw)=10 → scaled = 0.10
        // 预期 raw delta = (0xFFFFFFFF - 4294967290) + 10 + 1 = 5 + 10 + 1 = 16
        // 预期 scaled delta = 16 × 0.01 = 0.16
        BigDecimal scale = new BigDecimal("0.01");
        AccumulatorTracker t = new AccumulatorTracker();
        t.observe("e", new BigDecimal("42949672.90"), scale);
        BigDecimal delta = t.observe("e", new BigDecimal("0.10"), scale);
        assertThat(delta).isEqualByComparingTo("0.16");
    }

    @Test
    void independentTsFields_haveSeparateState() {
        AccumulatorTracker t = new AccumulatorTracker();
        t.observe("energy_a", new BigDecimal("100"), BigDecimal.ONE);
        t.observe("energy_b", new BigDecimal("500"), BigDecimal.ONE);

        assertThat(t.observe("energy_a", new BigDecimal("110"), BigDecimal.ONE))
                .isEqualByComparingTo("10");
        assertThat(t.observe("energy_b", new BigDecimal("520"), BigDecimal.ONE))
                .isEqualByComparingTo("20");
    }

    @Test
    void clear_resetsState() {
        AccumulatorTracker t = new AccumulatorTracker();
        t.observe("e", new BigDecimal("100"), BigDecimal.ONE);
        t.observe("e", new BigDecimal("200"), BigDecimal.ONE);
        assertThat(t.size()).isEqualTo(1);
        t.clear();
        assertThat(t.size()).isZero();
        // After clear, next observe is "first" again → null
        assertThat(t.observe("e", new BigDecimal("300"), BigDecimal.ONE)).isNull();
    }

    @Test
    void nullScale_treatedAsOne() {
        AccumulatorTracker t = new AccumulatorTracker();
        t.observe("e", new BigDecimal("100"), null);
        assertThat(t.observe("e", new BigDecimal("103"), null)).isEqualByComparingTo("3");
    }

    @Test
    void zeroIncrement_returnsZero() {
        // 仪表停了；本周期 = 上周期
        AccumulatorTracker t = new AccumulatorTracker();
        t.observe("e", new BigDecimal("100"), BigDecimal.ONE);
        assertThat(t.observe("e", new BigDecimal("100"), BigDecimal.ONE)).isEqualByComparingTo("0");
    }
}
