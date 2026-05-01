package com.ems.collector.protocol;

import jakarta.validation.constraints.*;

public record MqttPoint(
    @NotBlank String key,
    @NotBlank String topic,
    @NotBlank String jsonPath,
    String unit,
    String timestampJsonPath
) implements PointConfig {}
