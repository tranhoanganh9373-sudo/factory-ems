package com.ems.meter.dto;

import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FeedInTariff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record FeedInTariffDTO(
        Long id,
        String region,
        EnergySource energySource,
        String periodType,
        LocalDate effectiveFrom,
        BigDecimal price,
        OffsetDateTime createdAt
) {
    public static FeedInTariffDTO from(FeedInTariff e) {
        return new FeedInTariffDTO(
                e.getId(),
                e.getRegion(),
                e.getEnergySource(),
                e.getPeriodType(),
                e.getEffectiveFrom(),
                e.getPrice(),
                e.getCreatedAt()
        );
    }
}
