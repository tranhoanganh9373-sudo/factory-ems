package com.ems.timeseries.query;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 默认占位实现：rollup 边界 = Instant.MIN，意味着所有数据都通过 InfluxDB 现算。
 * 仅在没有真实 RollupReader bean 时启用（见 RollupReaderAutoConfig）。
 */
public class NoopRollupReader implements RollupReaderPort {

    @Override
    public Map<Long, List<TimePoint>> readBuckets(Collection<Long> meterIds, TimeRange range, Granularity granularity) {
        return Collections.emptyMap();
    }

    @Override
    public Map<Long, Double> sumByMeter(Collection<Long> meterIds, TimeRange range, Granularity granularity) {
        return Collections.emptyMap();
    }

    @Override
    public Instant rollupBoundary(Granularity granularity) {
        return Instant.MIN;
    }
}
