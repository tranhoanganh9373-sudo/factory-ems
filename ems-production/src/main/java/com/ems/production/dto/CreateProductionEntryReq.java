package com.ems.production.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateProductionEntryReq(
        @NotNull Long orgNodeId,
        @NotNull Long shiftId,
        @NotNull LocalDate entryDate,
        @NotBlank @Size(max = 64) String productCode,
        @NotNull @DecimalMin("0") BigDecimal quantity,
        @NotBlank @Size(max = 16) String unit,
        @Size(max = 255) String remark
) {}
