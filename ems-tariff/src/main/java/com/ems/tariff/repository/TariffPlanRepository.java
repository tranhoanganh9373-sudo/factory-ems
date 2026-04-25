package com.ems.tariff.repository;

import com.ems.tariff.entity.TariffPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TariffPlanRepository extends JpaRepository<TariffPlan, Long> {

    boolean existsByName(String name);

    List<TariffPlan> findAllByEnabledTrueOrderByEffectiveFromDesc();

    List<TariffPlan> findByEnergyTypeIdAndEnabledTrue(Long energyTypeId);

    @Query("SELECT p FROM TariffPlan p WHERE p.enabled = true " +
           "AND p.energyTypeId = :energyTypeId " +
           "AND p.effectiveFrom <= :at " +
           "AND (p.effectiveTo IS NULL OR p.effectiveTo >= :at) " +
           "ORDER BY p.effectiveFrom DESC " +
           "LIMIT 1")
    Optional<TariffPlan> findFirstActiveByEnergyTypeId(
            @Param("energyTypeId") Long energyTypeId,
            @Param("at") LocalDate at);
}
