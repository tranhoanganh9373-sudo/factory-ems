package com.ems.cost.dto;

import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.EnergyTypeCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CostLineDTO(
        Long id,
        Long runId,
        Long ruleId,
        Long targetOrgId,
        EnergyTypeCode energyType,
        BigDecimal quantity,
        BigDecimal amount,
        BigDecimal sharpQuantity,
        BigDecimal peakQuantity,
        BigDecimal flatQuantity,
        BigDecimal valleyQuantity,
        BigDecimal sharpAmount,
        BigDecimal peakAmount,
        BigDecimal flatAmount,
        BigDecimal valleyAmount,
        OffsetDateTime createdAt
) {
    public static CostLineDTO from(CostAllocationLine e) {
        return new CostLineDTO(
                e.getId(), e.getRunId(), e.getRuleId(), e.getTargetOrgId(),
                e.getEnergyType(), e.getQuantity(), e.getAmount(),
                e.getSharpQuantity(), e.getPeakQuantity(), e.getFlatQuantity(), e.getValleyQuantity(),
                e.getSharpAmount(), e.getPeakAmount(), e.getFlatAmount(), e.getValleyAmount(),
                e.getCreatedAt()
        );
    }
}
