package com.ems.alarm.dto;

public record WebhookTestResultDTO(int statusCode, long durationMs, String error) {}
