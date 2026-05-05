package com.ems.meter.dto;

import com.ems.meter.entity.CarbonFactor;
import com.ems.meter.entity.EnergySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record CarbonFactorDTO(
        Long id,
        String region,
        EnergySource energySource,
        LocalDate effectiveFrom,
        BigDecimal factorKgPerKwh,
        OffsetDateTime createdAt
) {
    public static CarbonFactorDTO from(CarbonFactor e) {
        return new CarbonFactorDTO(
                e.getId(),
                e.getRegion(),
                e.getEnergySource(),
                e.getEffectiveFrom(),
                e.getFactorKgPerKwh(),
                e.getCreatedAt()
        );
    }
}
