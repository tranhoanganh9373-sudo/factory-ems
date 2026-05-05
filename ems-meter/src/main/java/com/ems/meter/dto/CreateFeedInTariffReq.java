package com.ems.meter.dto;

import com.ems.meter.entity.EnergySource;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 上网电价新增请求。append-only：每次"调价"都是插入一条新的 effective_from 记录。
 *
 * <p>校验规则保持与 V2.6.0 schema CHECK 约束一致：
 * <ul>
 *   <li>period_type ∈ {PEAK, FLAT, VALLEY, TIP, SHARP}</li>
 *   <li>price ≥ 0</li>
 *   <li>energy_source 由 enum 自身限定</li>
 * </ul>
 */
public record CreateFeedInTariffReq(
        @NotBlank @Size(max = 64) String region,
        @NotNull EnergySource energySource,
        @NotBlank
        @Pattern(regexp = "PEAK|FLAT|VALLEY|TIP|SHARP",
                message = "must be one of PEAK, FLAT, VALLEY, TIP, SHARP")
        String periodType,
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin(value = "0.0", message = "must be >= 0") BigDecimal price
) {}
