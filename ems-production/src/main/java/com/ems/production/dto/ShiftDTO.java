package com.ems.production.dto;

import java.time.LocalTime;

public record ShiftDTO(Long id, String code, String name, LocalTime timeStart, LocalTime timeEnd,
                       boolean enabled, int sortOrder) {}
