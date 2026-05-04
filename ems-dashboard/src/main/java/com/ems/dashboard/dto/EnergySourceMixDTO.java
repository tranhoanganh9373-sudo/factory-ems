package com.ems.dashboard.dto;

import com.ems.meter.entity.EnergySource;

public record EnergySourceMixDTO(
    EnergySource energySource,
    String unit,
    Double value,
    Double share
) {}
