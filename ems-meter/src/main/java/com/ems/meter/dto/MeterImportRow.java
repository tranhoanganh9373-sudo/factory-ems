package com.ems.meter.dto;

import com.ems.core.constant.ValueKind;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.MeterRole;

/**
 * meters.csv 一行解析结果。前端拿到后再逐行调用 POST /meters 创建。
 *
 * <p>{@code channelName} 是 CSV 列里写的通道名（可空）；前端导入时会根据全量
 * channel 列表把它解析成 {@code channelId}。这一步交给前端是为了和 JSON 时代的
 * MeterBatchImportModal 行为保持一致——后端依旧只暴露 channelId 接口。
 *
 * <p>{@code valueKind} 可选；CSV 留空 → null → 服务端默认 {@link ValueKind#INTERVAL_DELTA}。
 *
 * <p>v1.2.0 PV 字段：{@code role}、{@code energySource}、{@code flowDirection} 均可选；
 * CSV 列缺失或留空 → null → 服务端默认 CONSUME / GRID / IMPORT。
 */
public record MeterImportRow(
    String code,
    String name,
    Long energyTypeId,
    Long orgNodeId,
    Boolean enabled,
    String channelName,
    String channelPointKey,
    ValueKind valueKind,
    MeterRole role,
    EnergySource energySource,
    FlowDirection flowDirection
) {}
