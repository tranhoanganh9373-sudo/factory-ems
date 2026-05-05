package com.ems.alarm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * v1.1.5 — config for hysteresis-gated topology-consistency auto-alarm.
 *
 * <p>Bound to {@code ems.alarm.topology.*}. All fields are required in the YAML
 * (no defaults inside the record so test code must construct explicitly with
 * literal thresholds — keeps test intent visible).
 */
@ConfigurationProperties(prefix = "ems.alarm.topology")
public record TopologyAlarmProperties(
        boolean enabled,
        String cron,
        String ttlCron,
        int historyRetentionDays,
        double enterThreshold,
        double exitThreshold
) {}
