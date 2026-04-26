package com.ems.cost.dto;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.EnergyTypeCode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public record CostRuleDTO(
        Long id,
        String code,
        String name,
        String description,
        EnergyTypeCode energyType,
        AllocationAlgorithm algorithm,
        Long sourceMeterId,
        List<Long> targetOrgIds,
        Map<String, Object> weights,
        Integer priority,
        Boolean enabled,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static CostRuleDTO from(CostAllocationRule e) {
        return new CostRuleDTO(
                e.getId(), e.getCode(), e.getName(), e.getDescription(),
                e.getEnergyType(), e.getAlgorithm(), e.getSourceMeterId(),
                e.getTargetOrgIds() == null ? List.of() : Arrays.asList(e.getTargetOrgIds()),
                e.getWeights(), e.getPriority(), e.getEnabled(),
                e.getEffectiveFrom(), e.getEffectiveTo(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
