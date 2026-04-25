package com.ems.timeseries.rollup.dto;

import com.ems.timeseries.model.Granularity;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record BackfillReq(
    @NotNull Granularity granularity,
    @NotNull Instant from,
    @NotNull Instant to,
    List<Long> meterIds   // null/empty = 全部 enabled
) {}
