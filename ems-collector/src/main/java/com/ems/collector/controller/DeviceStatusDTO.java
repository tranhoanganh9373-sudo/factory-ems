package com.ems.collector.controller;

import com.ems.collector.poller.DeviceState;

import java.time.Instant;

/**
 * REST 暴露的 device 状态。和 {@link com.ems.collector.poller.DeviceSnapshot} 同信息，
 * 加 {@code lastErrorTruncated} 把超长 error message 砍到 200 字符避免前端布局炸。
 */
public record DeviceStatusDTO(
        String deviceId,
        String meterCode,
        DeviceState state,
        Instant lastReadAt,
        Instant lastTransitionAt,
        long consecutiveErrors,
        long successCount,
        long failureCount,
        String lastError
) {
    public static DeviceStatusDTO from(com.ems.collector.poller.DeviceSnapshot s) {
        return new DeviceStatusDTO(
                s.deviceId(),
                s.meterCode(),
                s.state(),
                s.lastReadAt(),
                s.lastTransitionAt(),
                s.consecutiveErrors(),
                s.successCount(),
                s.failureCount(),
                truncate(s.lastError(), 200)
        );
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
