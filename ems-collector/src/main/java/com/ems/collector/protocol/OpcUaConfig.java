package com.ems.collector.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;

/**
 * OPC UA 协议配置。
 *
 * <p>{@link #pollInterval()} 仅在含 {@link SubscriptionMode#READ} 测点时使用；
 * 全订阅模式（所有 points 均为 SUBSCRIBE）下可为 {@code null}。
 */
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
