package com.ems.billing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 看板面板 ⑩ 当月成本分布。runId 为空 = 库里还没任何 SUCCESS run。
 * percent 为占当次 run 总金额的百分比（0..100，保留 2 位小数语义由前端控制）。
 */
public record CostDistributionDTO(
        Long runId,
        OffsetDateTime runFinishedAt,
        BigDecimal totalAmount,
        List<Item> items
) {
    public record Item(
            Long orgNodeId,
            String orgName,
            BigDecimal quantity,
            BigDecimal amount,
            double percent
    ) {}
}
