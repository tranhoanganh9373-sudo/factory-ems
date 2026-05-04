package com.ems.alarm.dto;

import java.util.List;

public record HealthSummaryDTO(
        long onlineCount,
        long offlineCount,
        long alarmCount,
        long maintenanceCount,
        long totalCount,
        List<TopOffender> topOffenders
) {
    public record TopOffender(Long deviceId, String deviceCode, long activeAlarmCount) {}
}
