package com.ems.meter.dto;

import jakarta.validation.constraints.NotNull;

public record BindParentMeterReq(@NotNull Long parentMeterId) {}
