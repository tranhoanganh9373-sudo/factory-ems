package com.ems.floorplan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

public record SetPointsReq(
        @NotNull @Valid List<PointEntry> points
) {
    public record PointEntry(
            @NotNull Long meterId,
            @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal xRatio,
            @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal yRatio,
            @Size(max = 64) String label
    ) {}
}
