package com.ems.dashboard.dto;

import java.time.LocalDate;
import java.util.List;

public record EnergyIntensityDTO(
        String electricityUnit,
        String productionUnit,
        List<Point> points
) {
    public record Point(LocalDate date, double electricity, double production, Double intensity) {}
}
