package com.ems.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record ProductionEntryDTO(
        Long id,
        Long orgNodeId,
        Long shiftId,
        LocalDate entryDate,
        String productCode,
        BigDecimal quantity,
        String unit,
        String remark,
        OffsetDateTime createdAt
) {}
