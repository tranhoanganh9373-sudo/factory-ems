package com.ems.alarm.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "ems.alarm")
public record AlarmProperties(
        @Min(1) int defaultSilentTimeoutSeconds,
        @Min(1) int defaultConsecutiveFailCount,
        @Min(10) int pollIntervalSeconds,
        @Min(0) int suppressionWindowSeconds,
        @Min(0) int webhookRetryMax,
        @NotEmpty List<@Positive Integer> webhookRetryBackoffSeconds,
        @Min(1000) int webhookTimeoutDefaultMs
) {}
