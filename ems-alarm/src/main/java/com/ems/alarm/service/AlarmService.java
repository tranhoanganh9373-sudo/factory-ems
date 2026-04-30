package com.ems.alarm.service;

import com.ems.alarm.dto.AlarmDTO;
import com.ems.alarm.dto.AlarmListItemDTO;
import com.ems.alarm.dto.HealthSummaryDTO;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.core.dto.PageDTO;

import java.time.OffsetDateTime;

public interface AlarmService {
    PageDTO<AlarmListItemDTO> list(AlarmStatus status, Long deviceId, AlarmType type,
                                   OffsetDateTime from, OffsetDateTime to,
                                   int page, int size);
    AlarmDTO getById(Long id);
    void ack(Long id, Long userId);
    void resolve(Long id);
    long countActive();
    HealthSummaryDTO healthSummary();
}
