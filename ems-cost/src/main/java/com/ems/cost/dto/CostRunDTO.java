package com.ems.cost.dto;

import com.ems.cost.entity.CostAllocationRun;
import com.ems.cost.entity.RunStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

public record CostRunDTO(
        Long id,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        RunStatus status,
        String algorithmVersion,
        BigDecimal totalAmount,
        List<Long> ruleIds,
        Long createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt,
        String errorMessage
) {
    public static CostRunDTO from(CostAllocationRun e) {
        return new CostRunDTO(
                e.getId(), e.getPeriodStart(), e.getPeriodEnd(),
                e.getStatus(), e.getAlgorithmVersion(), e.getTotalAmount(),
                e.getRuleIds() == null ? List.of() : Arrays.asList(e.getRuleIds()),
                e.getCreatedBy(), e.getCreatedAt(), e.getFinishedAt(), e.getErrorMessage()
        );
    }
}
