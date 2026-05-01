package com.ems.collector.protocol;

import jakarta.validation.constraints.*;

public record OpcUaPoint(
    @NotBlank String key,
    @NotBlank String nodeId,
    @NotNull SubscriptionMode mode,
    Double samplingIntervalMs,
    String unit
) implements PointConfig {}
