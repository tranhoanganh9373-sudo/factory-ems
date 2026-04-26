package com.ems.cost.service;

import java.time.OffsetDateTime;

/**
 * 一次分摊运行的不可变上下文。
 * 算法实现拿到 ctx 后用它读测点用量、查电价；不修改任何字段。
 */
public record AllocationContext(
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        MeterUsageReader meterUsage,
        com.ems.tariff.service.TariffPriceLookupService tariffLookup,
        MeterMetadataPort meterMetadata
) {
}
