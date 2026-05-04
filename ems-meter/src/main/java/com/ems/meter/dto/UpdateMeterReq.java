package com.ems.meter.dto;

import com.ems.core.constant.ValueKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateMeterReq(
    @NotBlank @Size(max = 64) String code,
    @NotBlank @Size(max = 128) String name,
    @NotNull Long energyTypeId,
    @NotNull Long orgNodeId,
    Boolean enabled,
    Long channelId,
    @Size(max = 64) String channelPointKey,
    ValueKind valueKind
) {}
