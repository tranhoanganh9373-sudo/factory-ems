package com.ems.cost.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 解析 PROPORTIONAL 规则的权重，归一化到 sum=1.0。
 *
 * 规则 weights JSONB 形如:
 *   { "basis": "FIXED|AREA|HEADCOUNT|PRODUCTION", "values": { "10": 0.4, "20": 0.6 } }
 *
 * - FIXED: 直接读 weights.values（必须存在；给出的是原始值，本类 normalize）
 * - AREA / HEADCOUNT: 从 org_nodes.area_m2 / headcount 读
 * - PRODUCTION: 从 production_entries 按 (org_node_id, periodStart..periodEnd) sum quantity
 *
 * 任何 basis 下，若解析出的总和为 0（或某 org 没有数据），fallback 到等权（1/N）+ warn 日志，避免 NaN。
 */
public interface WeightResolver {

    /**
     * @return map(orgId → 归一化权重)；保证 sum ≈ 1.0；保证 keySet == targetOrgIds 集合
     */
    Map<Long, java.math.BigDecimal> resolve(
            WeightBasis basis,
            List<Long> targetOrgIds,
            Map<String, Object> rawWeights,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd);
}
