package com.ems.dashboard.dto;

import java.time.Instant;

/**
 * 看板查询通用参数：range + 可选 from/to（CUSTOM 时必填）+ 可选 orgNodeId / energyType。
 * 由 Controller 解析查询参数，由 RangeResolver 转换为 [start, end) 时间区间。
 */
public record RangeQuery(
    RangeType range,
    Instant from,
    Instant to,
    Long orgNodeId,
    String energyType
) {}
