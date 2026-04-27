package com.ems.collector.poller;

import java.time.Instant;

/**
 * Read-only 状态快照，给 {@code GET /api/v1/collector/status} 与 health indicator 用。
 *
 * <p>所有字段在快照时刻取值；一致性由 {@link DevicePoller#snapshot()} 内部加锁保证。
 */
public record DeviceSnapshot(
        String deviceId,
        String meterCode,
        DeviceState state,
        Instant lastReadAt,
        Instant lastTransitionAt,
        long consecutiveErrors,
        long successCount,
        long failureCount,
        String lastError
) {}
