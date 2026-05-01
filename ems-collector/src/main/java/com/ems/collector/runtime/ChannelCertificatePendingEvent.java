package com.ems.collector.runtime;

import java.time.Instant;

/**
 * 当 OPC UA 服务端证书未在 trusted 目录中时，由 OpcUaTransport 发布。
 * 消费者：{@code com.ems.alarm.service.impl.CertificatePendingListener}（创建 OPC_UA_CERT_PENDING 告警）。
 */
public record ChannelCertificatePendingEvent(
        Long channelId,
        String endpointUrl,
        String thumbprint,
        String subjectDn,
        Instant occurredAt
) {}
