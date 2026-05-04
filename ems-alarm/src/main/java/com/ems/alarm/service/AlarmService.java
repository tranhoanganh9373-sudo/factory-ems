package com.ems.alarm.service;

import com.ems.alarm.dto.AlarmDTO;
import com.ems.alarm.dto.AlarmListItemDTO;
import com.ems.alarm.dto.HealthSummaryDTO;
import com.ems.alarm.dto.MeterOnlineState;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.core.dto.PageDTO;

import java.time.OffsetDateTime;
import java.util.Map;

public interface AlarmService {
    PageDTO<AlarmListItemDTO> list(AlarmStatus status, Long deviceId, AlarmType type,
                                   OffsetDateTime from, OffsetDateTime to,
                                   int page, int size);
    AlarmDTO getById(Long id);
    void ack(Long id, Long userId);
    void resolve(Long id);
    long countActive();
    HealthSummaryDTO healthSummary();

    /**
     * 返回每个 meter 的实时采集状态：ONLINE / OFFLINE / MAINTENANCE。
     * 与 {@link #healthSummary()} 同口径（freshness 窗口 + 维护标记），但按 meter id 维度返回，
     * 供表计列表页直接联表渲染状态列。未启用 meter 不在结果中（前端按 enabled 单独处理）。
     */
    Map<Long, MeterOnlineState> meterOnlineStatuses();
}
