package com.ems.production.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record UpdateProductionEntryReq(
        @NotNull @DecimalMin("0") BigDecimal quantity,
        @NotBlank String unit,
        @Size(max = 255) String remark
) {}
