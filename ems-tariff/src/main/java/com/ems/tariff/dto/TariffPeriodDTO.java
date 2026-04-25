package com.ems.tariff.dto;

import java.math.BigDecimal;
import java.time.LocalTime;

public record TariffPeriodDTO(
        Long id,
        String periodType,
        LocalTime timeStart,
        LocalTime timeEnd,
        BigDecimal pricePerUnit
) {}
