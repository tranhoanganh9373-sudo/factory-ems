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

    /** Admin UI 列表：按 (region, energy_source) 分组、effective_from 倒序，便于前端识别"当前生效"行。 */
    List<CarbonFactor> findAllByOrderByRegionAscEnergySourceAscEffectiveFromDesc();

    /** 唯一键去重检测——和 V2.6.0 schema 上的 UNIQUE (region, energy_source, effective_from) 一致。 */
    boolean existsByRegionAndEnergySourceAndEffectiveFrom(
            String region, EnergySource energySource, LocalDate effectiveFrom);
}
