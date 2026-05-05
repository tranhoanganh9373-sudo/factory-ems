package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.WebhookConfig;

public interface WebhookChannel {
    void sendTriggered(Alarm a);
    /** v1.1.5 — fired for auto-recovery of selected alarm types (topology). */
    void sendResolved(Alarm a);
    void retryDelivery(Long deliveryLogId);
    WebhookTestResult test(WebhookConfig cfg, Alarm sampleAlarm, String deviceCode, String deviceName);

    record WebhookTestResult(int statusCode, long durationMs, String error) {}
}
