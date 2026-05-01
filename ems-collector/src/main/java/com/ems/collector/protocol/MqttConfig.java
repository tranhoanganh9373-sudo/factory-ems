package com.ems.collector.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    String lastWillTopic,
    String lastWillPayload,
    @Min(0) @Max(2) int lastWillQos,
    boolean lastWillRetained,
    @Valid @NotEmpty List<MqttPoint> points
) implements ChannelConfig {
    public String protocol() { return "MQTT"; }
    public Duration pollInterval() { return null; }

    @JsonIgnore
    @AssertTrue(message = "lastWillTopic and lastWillPayload must both be set or both be null")
    public boolean isLastWillConfigConsistent() {
        boolean topicSet = lastWillTopic != null && !lastWillTopic.isBlank();
        boolean payloadSet = lastWillPayload != null && !lastWillPayload.isBlank();
        return topicSet == payloadSet;
    }
}
