package com.ems.collector.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;

public record ModbusTcpConfig(
    @NotBlank String host,
    @Min(1) @Max(65535) int port,
    int unitId,
    @NotNull Duration pollInterval,
    Duration timeout,
    @Valid @NotEmpty List<ModbusPoint> points
) implements ChannelConfig {
    public String protocol() { return "MODBUS_TCP"; }
}
