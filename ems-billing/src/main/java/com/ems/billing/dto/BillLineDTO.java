package com.ems.billing.dto;

import com.ems.billing.entity.BillLine;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BillLineDTO(
        Long id,
        Long billId,
        Long ruleId,
        String sourceLabel,
        BigDecimal quantity,
        BigDecimal amount,
        OffsetDateTime createdAt
) {
    public static BillLineDTO from(BillLine e) {
        return new BillLineDTO(
                e.getId(), e.getBillId(), e.getRuleId(), e.getSourceLabel(),
                e.getQuantity(), e.getAmount(), e.getCreatedAt()
        );
    }
}
