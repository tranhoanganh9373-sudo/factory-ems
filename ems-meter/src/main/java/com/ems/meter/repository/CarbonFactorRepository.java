package com.ems.meter.repository;

import com.ems.meter.entity.CarbonFactor;
import com.ems.meter.entity.EnergySource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CarbonFactorRepository extends JpaRepository<CarbonFactor, Long> {

    /** 取在 asOf 当日生效（最近一次 effective_from <= asOf）的因子。 */
    @Query("""
        SELECT cf FROM CarbonFactor cf
        WHERE cf.region = :region
          AND cf.energySource = :source
          AND cf.effectiveFrom <= :asOf
        ORDER BY cf.effectiveFrom DESC
        """)
    List<CarbonFactor> findEffectiveOrdered(String region, EnergySource source, LocalDate asOf, PageRequest page);

    default Optional<CarbonFactor> findEffective(String region, EnergySource source, LocalDate asOf) {
        var rows = findEffectiveOrdered(region, source, asOf, PageRequest.of(0, 1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
