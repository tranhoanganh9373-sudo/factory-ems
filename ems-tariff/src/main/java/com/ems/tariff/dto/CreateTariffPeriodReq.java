package com.ems.tariff.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalTime;

public record CreateTariffPeriodReq(
        @NotBlank String periodType,
        @NotNull LocalTime timeStart,
        @NotNull LocalTime timeEnd,
        @NotNull @DecimalMin("0") BigDecimal pricePerUnit
) {}
