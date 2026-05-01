package com.ems.collector.cert;

import java.time.Instant;

public record PendingCertificate(
        String thumbprint,
        Long channelId,
        String endpointUrl,
        Instant firstSeenAt,
        String subjectDn
) {}
