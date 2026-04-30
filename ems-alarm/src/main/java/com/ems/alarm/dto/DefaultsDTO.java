package com.ems.alarm.dto;

public record DefaultsDTO(int silentTimeoutSeconds, int consecutiveFailCount, int suppressionWindowSeconds) {}
