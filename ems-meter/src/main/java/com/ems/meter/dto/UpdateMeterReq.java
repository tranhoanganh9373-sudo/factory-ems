package com.ems.meter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateMeterReq(
    @NotBlank @Size(max = 128) String name,
    @NotNull Long energyTypeId,
    @NotNull Long orgNodeId,
    @NotBlank @Size(max = 64) String influxMeasurement,
    @NotBlank @Size(max = 64) String influxTagKey,
    @NotBlank @Size(max = 128) String influxTagValue,
    Boolean enabled,
    Long channelId
) {}
