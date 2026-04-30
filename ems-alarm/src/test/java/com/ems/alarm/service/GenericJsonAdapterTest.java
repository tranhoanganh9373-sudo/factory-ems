package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.service.adapter.GenericJsonAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenericJsonAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GenericJsonAdapter adapter = new GenericJsonAdapter(mapper);

    @Test
    @SuppressWarnings("unchecked")
    void payload_containsAllRequiredFields() throws Exception {
        Alarm a = buildAlarm();
        String json = adapter.buildPayload(a, "M-001", "Meter A1");
        Map<String, Object> parsed = mapper.readValue(json, Map.class);
        assertThat(parsed).containsKeys("event", "alarm_id", "device_id", "device_type",
                "device_code", "device_name", "alarm_type", "severity", "triggered_at");
        assertThat(parsed.get("event")).isEqualTo("alarm.triggered");
        assertThat(parsed.get("alarm_type")).isEqualTo("SILENT_TIMEOUT");
        assertThat(parsed.get("device_code")).isEqualTo("M-001");
    }

    @Test
    void timestamp_isIso8601WithOffset() {
        Alarm a = buildAlarm();
        a.setTriggeredAt(OffsetDateTime.parse("2026-04-29T08:15:30+08:00"));
        String json = adapter.buildPayload(a, "M-001", "Meter A1");
        assertThat(json).contains("\"triggered_at\":\"2026-04-29T08:15:30+08:00\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvedStatus_yieldsAlarmResolvedEvent() throws Exception {
        Alarm a = buildAlarm();
        a.setStatus(AlarmStatus.RESOLVED);
        String json = adapter.buildPayload(a, "M-001", "Meter A1");
        Map<String, Object> parsed = mapper.readValue(json, Map.class);
        assertThat(parsed.get("event")).isEqualTo("alarm.resolved");
    }

    @Test
    void getType_isGenericJson() {
        assertThat(adapter.getType()).isEqualTo("GENERIC_JSON");
    }

    private Alarm buildAlarm() {
        Alarm a = new Alarm();
        a.setId(99L);
        a.setDeviceId(1L);
        a.setDeviceType("METER");
        a.setAlarmType(AlarmType.SILENT_TIMEOUT);
        a.setSeverity("WARNING");
        a.setStatus(AlarmStatus.ACTIVE);
        a.setTriggeredAt(OffsetDateTime.parse("2026-04-29T10:00:00Z"));
        return a;
    }
}
