package com.ems.dashboard.dto;

/**
 * KPI 卡：每个能源品类一行。
 *  - total: 当期总量
 *  - mom:   环比（上一同等长度窗口） = (cur - prev) / prev，prev = 0 时返回 null
 *  - yoy:   同比（去年同期） = (cur - prevYear) / prevYear，prevYear = 0 时返回 null
 */
public record KpiDTO(
    String energyType,
    String unit,
    Double total,
    Double mom,
    Double yoy
) {}
