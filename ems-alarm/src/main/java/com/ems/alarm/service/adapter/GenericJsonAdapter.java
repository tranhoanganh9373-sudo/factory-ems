package com.ems.alarm.service.adapter;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.ResolvedReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GenericJsonAdapter implements WebhookAdapter {

    private final ObjectMapper mapper;

    public GenericJsonAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String getType() {
        return "GENERIC_JSON";
    }

    @Override
    public String buildPayload(Alarm a, String deviceCode, String deviceName) {
        Map<String, Object> p = new LinkedHashMap<>();
        boolean isResolveEvent = a.getStatus() == AlarmStatus.RESOLVED
                || (a.getStatus() == AlarmStatus.ACKED && a.getResolvedReason() == ResolvedReason.AUTO);
        p.put("event", isResolveEvent ? "alarm.resolved" : "alarm.triggered");
        p.put("alarm_id", a.getId());
        p.put("device_id", a.getDeviceId());
        p.put("device_type", a.getDeviceType());
        p.put("device_code", deviceCode);
        p.put("device_name", deviceName);
        p.put("alarm_type", a.getAlarmType().name());
        p.put("severity", a.getSeverity());
        p.put("triggered_at", a.getTriggeredAt().toString());
        if (a.getLastSeenAt() != null) p.put("last_seen_at", a.getLastSeenAt().toString());
        if (a.getDetail() != null) p.put("detail", a.getDetail());
        try {
            return mapper.writeValueAsString(p);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
