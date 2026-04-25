package com.ems.tariff.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record CreateTariffPlanReq(
        @NotBlank String name,
        @NotNull Long energyTypeId,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        List<CreateTariffPeriodReq> periods
) {}
