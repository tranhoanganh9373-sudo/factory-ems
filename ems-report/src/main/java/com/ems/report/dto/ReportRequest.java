package com.ems.report.dto;

import com.ems.timeseries.model.Granularity;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * Ad-hoc 报表查询参数。
 *  - from/to: 时段（必填，from &lt; to）
 *  - granularity: 聚合粒度（HOUR / DAY / MONTH 走 rollup + 现算合并；MINUTE 仅 24h 内）
 *  - orgNodeId: 限定子树（可空 → 全部可见节点）
 *  - energyTypes: 能源品类过滤（可空 → 不过滤）
 *  - meterIds: 指定测点（可空 → 不过滤；与 orgNodeId/energyTypes 同时存在时取交集）
 *
 * <p>{@code @NotNull} 仅在 {@code @Valid @RequestBody} 上下文里生效；服务/测试直接构造仍可传 null。
 * GET /ad-hoc 用 @RequestParam 已隐含必填，不依赖此处校验。
 */
public record ReportRequest(
    @NotNull Instant from,
    @NotNull Instant to,
    @NotNull Granularity granularity,
    Long orgNodeId,
    List<String> energyTypes,
    List<Long> meterIds
) {}
