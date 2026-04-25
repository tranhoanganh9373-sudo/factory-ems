package com.ems.tariff.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;

public record UpdateTariffPlanReq(
        @NotBlank String name,
        LocalDate effectiveTo,
        Boolean enabled,
        List<CreateTariffPeriodReq> periods
) {}
