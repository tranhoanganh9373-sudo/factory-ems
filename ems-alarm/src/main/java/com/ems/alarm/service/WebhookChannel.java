package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.WebhookConfig;

public interface WebhookChannel {
    void sendTriggered(Alarm a);
    void retryDelivery(Long deliveryLogId);
    WebhookTestResult test(WebhookConfig cfg, Alarm sampleAlarm, String deviceCode, String deviceName);

    record WebhookTestResult(int statusCode, long durationMs, String error) {}
}
