package com.ems.tariff.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record TariffPlanDTO(
        Long id,
        String name,
        Long energyTypeId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean enabled,
        List<TariffPeriodDTO> periods,
        OffsetDateTime createdAt
) {}
