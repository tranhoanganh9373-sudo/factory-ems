package com.ems.collector.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;

public record ModbusRtuConfig(
    @NotBlank String serialPort,
    @Min(1200) int baudRate,
    int dataBits,
    int stopBits,
    @NotBlank String parity,
    int unitId,
    @NotNull Duration pollInterval,
    Duration timeout,
    @Valid @NotEmpty List<ModbusPoint> points
) implements ChannelConfig {
    public String protocol() { return "MODBUS_RTU"; }
}
