package com.ems.dashboard.dto;

/** 能源构成饼图：单个能源品类在区间内的份额（按归一到的可比单位求和占比）。 */
public record CompositionDTO(
    String energyType,
    String unit,
    Double total,
    Double share
) {}
