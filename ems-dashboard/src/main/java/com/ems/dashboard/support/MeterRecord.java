package com.ems.dashboard.support;

/**
 * 看板内部使用的扁平测点视图：包含 dashboard 计算所需的全部字段。
 * 由 DashboardSupport 一次性 join 测点 + 能源品类得到，避免下游各 panel 重复查表。
 */
public record MeterRecord(
    Long meterId,
    String code,
    String name,
    Long orgNodeId,
    String influxTagValue,
    Long energyTypeId,
    String energyTypeCode,
    String unit,
    Boolean enabled
) {}
