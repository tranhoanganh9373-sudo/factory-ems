package com.ems.cost.repository;

import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.EnergyTypeCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CostAllocationRuleRepository extends JpaRepository<CostAllocationRule, Long> {

    Optional<CostAllocationRule> findByCode(String code);

    boolean existsByCode(String code);

    List<CostAllocationRule> findAllByEnabledTrueOrderByPriorityAsc();

    @Query("SELECT r FROM CostAllocationRule r WHERE r.enabled = true " +
           "AND r.energyType = :energyType " +
           "AND r.effectiveFrom <= :at " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo >= :at) " +
           "ORDER BY r.priority ASC")
    List<CostAllocationRule> findActiveByEnergyType(
            @Param("energyType") EnergyTypeCode energyType,
            @Param("at") LocalDate at);

    @Query("SELECT r FROM CostAllocationRule r WHERE r.enabled = true " +
           "AND r.effectiveFrom <= :at " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo >= :at) " +
           "ORDER BY r.priority ASC")
    List<CostAllocationRule> findAllActive(@Param("at") LocalDate at);
}
