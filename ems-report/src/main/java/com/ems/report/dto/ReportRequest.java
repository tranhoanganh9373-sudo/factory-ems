package com.ems.report.dto;

import com.ems.timeseries.model.Granularity;

import java.time.Instant;
import java.util.List;

/**
 * Ad-hoc 报表查询参数。所有字段在 Controller 层经 @DateTimeFormat / 显式校验。
 *  - from/to: 时段（必填，from < to）
 *  - granularity: 聚合粒度（HOUR / DAY / MONTH 走 rollup + 现算合并；MINUTE 仅 24h 内）
 *  - orgNodeId: 限定子树（可空 → 全部可见节点）
 *  - energyTypes: 能源品类过滤（可空 → 不过滤）
 *  - meterIds: 指定测点（可空 → 不过滤；与 orgNodeId/energyTypes 同时存在时取交集）
 */
public record ReportRequest(
    Instant from,
    Instant to,
    Granularity granularity,
    Long orgNodeId,
    List<String> energyTypes,
    List<Long> meterIds
) {}
