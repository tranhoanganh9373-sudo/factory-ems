package com.ems.tariff.service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 批量查询能源品类在一段时间窗口内的"每小时所属时段 + 单价"。
 * 给成本分摊引擎用：让一个 4 段电价方案 × 一段月度窗口的 720 个小时只走一次 plan/period 查询。
 *
 * 单点查询请继续用 {@link TariffService#resolvePrice(Long, OffsetDateTime)}。
 */
public interface TariffPriceLookupService {

    /**
     * 返回 [periodStart, periodEnd) 之间每个整点小时桶的时段类型和单价。
     * 跨零点时段沿用 v1 规则（time_start &gt; time_end 表示跨日，命中 t&gt;=start || t&lt;end）。
     * 同一天若有多个 plan 生效，取 effective_from DESC 第一个匹配的 plan。
     *
     * @param energyTypeId   能源品类 id（来自 meter.energy_type_id）
     * @param periodStart    窗口起点（含）
     * @param periodEnd      窗口终点（不含）
     * @return 每小时一条记录；若该小时落不到任何 period，periodType 为 "FLAT"，价格 = 0
     */
    List<HourPrice> batch(Long energyTypeId, OffsetDateTime periodStart, OffsetDateTime periodEnd);
}
