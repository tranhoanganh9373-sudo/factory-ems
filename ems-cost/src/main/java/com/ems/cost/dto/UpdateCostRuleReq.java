package com.ems.cost.dto;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.EnergyTypeCode;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Partial update — null = "leave unchanged". `code` cannot be changed once created.
 */
public record UpdateCostRuleReq(
        @Size(max = 128) String name,
        String description,
        EnergyTypeCode energyType,
        AllocationAlgorithm algorithm,
        Long sourceMeterId,
        List<Long> targetOrgIds,
        Map<String, Object> weights,
        Integer priority,
        Boolean enabled,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
