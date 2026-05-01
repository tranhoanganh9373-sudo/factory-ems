package com.ems.collector.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;

public record VirtualConfig(
    @NotNull Duration pollInterval,
    @Valid @NotEmpty List<VirtualPoint> points
) implements ChannelConfig {
    public String protocol() { return "VIRTUAL"; }
}
