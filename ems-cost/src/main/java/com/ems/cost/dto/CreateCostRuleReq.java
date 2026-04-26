package com.ems.cost.dto;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.EnergyTypeCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record CreateCostRuleReq(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 128) String name,
        String description,
        @NotNull EnergyTypeCode energyType,
        @NotNull AllocationAlgorithm algorithm,
        @NotNull Long sourceMeterId,
        @NotEmpty List<Long> targetOrgIds,
        Map<String, Object> weights,
        Integer priority,
        Boolean enabled,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
