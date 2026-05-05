package com.ems.meter.dto;

import com.ems.meter.entity.EnergySource;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 碳排因子新增请求。append-only：调整因子时插入新的 effective_from 行，旧值保留供历史报表回放。
 *
 * <p>校验规则保持与 V2.6.0 schema CHECK 约束一致：factor_kg_per_kwh ≥ 0；energy_source 由 enum 限定。
 */
public record CreateCarbonFactorReq(
        @NotBlank @Size(max = 64) String region,
        @NotNull EnergySource energySource,
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin(value = "0.0", message = "must be >= 0") BigDecimal factorKgPerKwh
) {}
