package com.ems.collector.protocol;

import jakarta.validation.constraints.*;
import java.util.Map;

public record VirtualPoint(
    @NotBlank String key,
    @NotNull VirtualMode mode,
    @NotNull Map<String, Double> params,
    String unit
) implements PointConfig {}
