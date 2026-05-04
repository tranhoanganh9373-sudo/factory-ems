package com.ems.report.carbon;

import java.math.BigDecimal;

public record CarbonReportDTO(
    BigDecimal selfConsumptionKwh,
    BigDecimal gridFactor,
    BigDecimal solarFactor,
    BigDecimal reductionKg
) {}
