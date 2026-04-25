package com.ems.tariff.dto;

import java.math.BigDecimal;

public record ResolvedPriceDTO(
        String periodType,
        BigDecimal pricePerUnit,
        Long planId
) {}
