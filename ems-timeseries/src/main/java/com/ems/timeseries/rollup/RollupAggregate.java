package com.ems.timeseries.rollup;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 一个桶内的聚合结果（sum/avg/max/min/count）。count == 0 时不应 upsert。
 */
public final class RollupAggregate {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private final BigDecimal sum;
    private final BigDecimal max;
    private final BigDecimal min;
    private final int count;

    private RollupAggregate(BigDecimal sum, BigDecimal max, BigDecimal min, int count) {
        this.sum = sum; this.max = max; this.min = min; this.count = count;
    }

    public static RollupAggregate empty() {
        return new RollupAggregate(BigDecimal.ZERO, null, null, 0);
    }

    public RollupAggregate add(double v) {
        BigDecimal bv = BigDecimal.valueOf(v);
        BigDecimal newMax = (max == null || bv.compareTo(max) > 0) ? bv : max;
        BigDecimal newMin = (min == null || bv.compareTo(min) < 0) ? bv : min;
        return new RollupAggregate(sum.add(bv), newMax, newMin, count + 1);
    }

    public boolean isEmpty() { return count == 0; }
    public BigDecimal sum() { return sum; }
    public BigDecimal max() { return max; }
    public BigDecimal min() { return min; }
    public int count() { return count; }
    public BigDecimal avg() {
        if (count == 0) return BigDecimal.ZERO;
        return sum.divide(BigDecimal.valueOf(count), MC).setScale(6, RoundingMode.HALF_UP);
    }
}
