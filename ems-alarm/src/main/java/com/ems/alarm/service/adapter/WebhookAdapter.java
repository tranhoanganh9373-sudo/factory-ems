package com.ems.alarm.service.adapter;

import com.ems.alarm.entity.Alarm;

public interface WebhookAdapter {
    String getType();
    String buildPayload(Alarm alarm, String deviceCode, String deviceName);
}
