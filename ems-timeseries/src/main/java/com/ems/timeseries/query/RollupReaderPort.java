package com.ems.timeseries.query;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 读已 rollup 的预聚合数据。Phase D 由 RollupReader 实现（查 ts_rollup_hourly/daily/monthly）。
 * Phase C 提供 NoOp 默认实现以便单测/契约测试不依赖 PG。
 */
public interface RollupReaderPort {

    /** 返回 [start, end) 区间内已 rollup 的桶（不包含未 rollup 的边界桶）。 */
    Map<Long, List<TimePoint>> readBuckets(Collection<Long> meterIds, TimeRange range, Granularity granularity);

    /** 返回每个 meter 在 [start, end) 区间内已 rollup 段的 sum。 */
    Map<Long, Double> sumByMeter(Collection<Long> meterIds, TimeRange range, Granularity granularity);

    /**
     * 已 rollup 数据的"截止时刻"：早于此时刻的桶都已落盘到 PG，晚于此时刻的桶需 InfluxDB 现算。
     * Phase C 默认返回 range.start（即不依赖 rollup，全量现算）；Phase D 替换为 max(rollup_ts) + 1 桶。
     */
    java.time.Instant rollupBoundary(Granularity granularity);
}
