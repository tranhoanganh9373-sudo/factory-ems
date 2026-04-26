package com.ems.cost.repository;

import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.EnergyTypeCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CostAllocationLineRepository extends JpaRepository<CostAllocationLine, Long> {

    List<CostAllocationLine> findByRunId(Long runId);

    List<CostAllocationLine> findByRunIdAndTargetOrgId(Long runId, Long targetOrgId);

    List<CostAllocationLine> findByRunIdAndEnergyType(Long runId, EnergyTypeCode energyType);

    void deleteByRunId(Long runId);
}
