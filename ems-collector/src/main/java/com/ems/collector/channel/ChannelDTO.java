package com.ems.collector.channel;

import com.ems.collector.protocol.ChannelConfig;

import java.time.Instant;

public record ChannelDTO(
    Long id,
    String name,
    String protocol,
    boolean enabled,
    boolean isVirtual,
    ChannelConfig protocolConfig,
    String description,
    Instant createdAt,
    Instant updatedAt
) {
    public static ChannelDTO from(Channel c) {
        return new ChannelDTO(
            c.getId(),
            c.getName(),
            c.getProtocol(),
            c.isEnabled(),
            c.isVirtual(),
            c.getProtocolConfig(),
            c.getDescription(),
            c.getCreatedAt(),
            c.getUpdatedAt()
        );
    }
}
