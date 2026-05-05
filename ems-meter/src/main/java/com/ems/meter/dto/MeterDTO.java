package com.ems.meter.dto;

import com.ems.core.constant.ValueKind;

import java.time.OffsetDateTime;

public record MeterDTO(
    Long id,
    String code,
    String name,
    Long energyTypeId,
    String energyTypeCode,
    String energyTypeName,
    String unit,
    Long orgNodeId,
    String influxMeasurement,
    String influxTagKey,
    String influxTagValue,
    Boolean enabled,
    Long channelId,
    String channelPointKey,
    Long parentMeterId,
    ValueKind valueKind,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
