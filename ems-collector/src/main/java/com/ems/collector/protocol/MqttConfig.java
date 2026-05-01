package com.ems.collector.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;

/**
 * MQTT 协议配置。事件驱动，{@link #pollInterval()} 永远返回 {@code null}。
 */
public record MqttConfig(
    @NotBlank String brokerUrl,
    @NotBlank String clientId,
    String usernameRef,
    String passwordRef,
    String tlsCaCertRef,
    @Min(0) @Max(2) int qos,
    boolean cleanSession,
    @NotNull Duration keepAlive,
    @Valid @NotEmpty List<MqttPoint> points
) implements ChannelConfig {
    public String protocol() { return "MQTT"; }
    public Duration pollInterval() { return null; }
}
