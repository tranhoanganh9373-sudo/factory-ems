package com.ems.cost.service;

import com.ems.meter.entity.EnergySource;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface FeedInRevenueService {

    /**
     * 区间内某 org 的上网卖电收入：
     *   Σ_period (export_kwh_in_period × tariff_in_period)
     * region 当前从默认 'CN' 解析（未来可加 OrgNode.region 字段）。
     * 找不到生效的 FeedInTariff 行 → IllegalStateException with clear message.
     * 若 org 下无 EXPORT 类型 meter 或 export 用量为零 → 返回 ZERO。
     */
    BigDecimal computeRevenue(Long orgNodeId, EnergySource source, LocalDate from, LocalDate to);
}
