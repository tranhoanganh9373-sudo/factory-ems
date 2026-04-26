package com.ems.cost.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 测点用量读取端口（背后 = ts_rollup_hourly）。
 * 算法只看到聚合接口，不直接 import RollupHourlyRepository。
 */
public interface MeterUsageReader {

    /**
     * 返回 [start, end) 之间每个小时桶的 sum_value（kWh / m³ / 等）。
     * 桶顺序按 hourTs ASC。缺失小时 = 不出现，调用方按需 zero-fill。
     */
    List<HourlyUsage> hourly(Long meterId, OffsetDateTime start, OffsetDateTime end);

    /**
     * 区间内 sum_value 的合计。比迭代 hourly 更快，用于不需要按段拆分的场景。
     */
    BigDecimal totalUsage(Long meterId, OffsetDateTime start, OffsetDateTime end);

    /**
     * 多个 meter 的合计，一次查询返回。返回 map 的 key 是 meterId。
     * meter 没有数据的不出现在返回 map 里。
     */
    Map<Long, BigDecimal> totalUsageBatch(List<Long> meterIds, OffsetDateTime start, OffsetDateTime end);

    record HourlyUsage(OffsetDateTime hourTs, BigDecimal sumValue) {}
}
