package com.ems.timeseries.query;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Phase C 占位实现：rollup 边界 = Instant.MIN，意味着所有数据都通过 InfluxDB 现算。
 * Phase D 提供真正的 RollupReaderImpl 后会被替代（@ConditionalOnMissingBean）。
 */
@Component
@ConditionalOnMissingBean(RollupReaderPort.class)
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
