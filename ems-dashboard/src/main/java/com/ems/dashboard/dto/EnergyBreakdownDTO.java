package com.ems.dashboard.dto;

import java.util.List;

/**
 * 用电细分：把"可见集合的根表"按 直接子表 + 其他/未分摊 拆开。
 * 用于 dashboard "能耗构成（按测点）"面板。
 *
 * <p>语义：rootTotal = Σ(visible roots);  Σitems[].value 应等于 rootTotal。
 *   - covered（覆盖部分）= 根表的直接子表读数之和（仅可见的子表）
 *   - residual（未分摊）= rootTotal - covered；可能为负（数据/配置异常）
 *
 * <p>每个 item 标记 isResidual=true 即"其他/未分摊"行；其余为子表行。
 */
public record EnergyBreakdownDTO(
    String energyType,
    String unit,
    Double rootTotal,
    List<Item> items
) {
    public record Item(
        Long meterId,       // residual 行用 null
        String code,        // residual 行可空
        String name,        // residual 行 = "其他/未分摊"
        Double value,
        Double share,       // 占 rootTotal 的比例；rootTotal=0 时为 null
        Boolean isResidual
    ) {}
}
