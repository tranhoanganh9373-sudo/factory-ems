package com.ems.collector.protocol;

import jakarta.validation.constraints.*;

public record ModbusPoint(
    @NotBlank String key,
    @NotBlank String registerKind,
    @Min(0) int address,
    @Min(1) int quantity,
    @NotBlank String dataType,
    String byteOrder,
    Double scale,
    String unit
) implements PointConfig {}
