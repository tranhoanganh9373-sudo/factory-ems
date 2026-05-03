package com.ems.collector.runtime;

import java.time.Instant;

/**
 * 当管理员通过 REST 端点信任一张 OPC UA 服务端证书时，由 CertificateApprovalController 发布。
 * 消费者：{@code com.ems.alarm.service.impl.CertificatePendingListener}（自动解除 OPC_UA_CERT_PENDING 报警）。
 */
public record ChannelCertificateApprovedEvent(
        Long channelId,
        String thumbprint,
        String approvedBy,
        Instant occurredAt
) {}
