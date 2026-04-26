package com.ems.tariff.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 一个小时桶对应的 (时段类型, 单价)。
 * hourStart 是该小时桶的起点（含），等价于 ts_rollup_hourly.bucket_start。
 */
public record HourPrice(
        OffsetDateTime hourStart,
        String periodType,
        BigDecimal pricePerUnit) {
}
