package com.ems.dashboard.dto;

import java.util.List;

public record TariffDistributionDTO(
        String unit,
        double total,
        List<Slice> slices
) {
    public record Slice(String periodType, double value, Double share) {}
}
