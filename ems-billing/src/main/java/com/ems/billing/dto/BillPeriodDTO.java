package com.ems.billing.dto;

import com.ems.billing.entity.BillPeriod;
import com.ems.billing.entity.BillPeriodStatus;

import java.time.OffsetDateTime;

public record BillPeriodDTO(
        Long id,
        String yearMonth,
        BillPeriodStatus status,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        OffsetDateTime closedAt,
        Long closedBy,
        OffsetDateTime lockedAt,
        Long lockedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static BillPeriodDTO from(BillPeriod e) {
        return new BillPeriodDTO(
                e.getId(), e.getYearMonth(), e.getStatus(),
                e.getPeriodStart(), e.getPeriodEnd(),
                e.getClosedAt(), e.getClosedBy(),
                e.getLockedAt(), e.getLockedBy(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
