package com.ems.billing.dto;

import com.ems.billing.entity.Bill;
import com.ems.cost.entity.EnergyTypeCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BillDTO(
        Long id,
        Long periodId,
        Long runId,
        Long orgNodeId,
        EnergyTypeCode energyType,
        BigDecimal quantity,
        BigDecimal amount,
        BigDecimal sharpAmount,
        BigDecimal peakAmount,
        BigDecimal flatAmount,
        BigDecimal valleyAmount,
        BigDecimal productionQty,
        BigDecimal unitCost,
        BigDecimal unitIntensity,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static BillDTO from(Bill e) {
        return new BillDTO(
                e.getId(), e.getPeriodId(), e.getRunId(), e.getOrgNodeId(),
                e.getEnergyType(),
                e.getQuantity(), e.getAmount(),
                e.getSharpAmount(), e.getPeakAmount(), e.getFlatAmount(), e.getValleyAmount(),
                e.getProductionQty(), e.getUnitCost(), e.getUnitIntensity(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
