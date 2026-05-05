package com.ems.dashboard.support;

import com.ems.core.constant.ValueKind;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.MeterRole;

/**
 * 看板内部使用的扁平测点视图：包含 dashboard 计算所需的全部字段。
 * 由 DashboardSupport 一次性 join 测点 + 能源品类得到，避免下游各 panel 重复查表。
 *
 * <p>{@code valueKind} 决定该 meter 在时序查询中的聚合算子（sum / last-first / integral）。
 * <p>{@code role / energySource / flowDirection} (v1.2.0 PV 接入) 决定 meter 在 KPI / 能耗
 * 构成 / 自发自用计算中的分组归属。
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
    Boolean enabled,
    ValueKind valueKind,
    MeterRole role,
    EnergySource energySource,
    FlowDirection flowDirection
) {}
