package com.ems.collector.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;

public record OpcUaConfig(
    @NotBlank String endpointUrl,
    @NotNull SecurityMode securityMode,
    String certRef,
    String certPasswordRef,
    String usernameRef,
    String passwordRef,
    Duration pollInterval,
    @Valid @NotEmpty List<OpcUaPoint> points
) implements ChannelConfig {
    public String protocol() { return "OPC_UA"; }
}
