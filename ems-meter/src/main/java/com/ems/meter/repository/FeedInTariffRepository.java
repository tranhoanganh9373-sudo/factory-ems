package com.ems.meter.repository;

import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FeedInTariff;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FeedInTariffRepository extends JpaRepository<FeedInTariff, Long> {

    @Query("""
        SELECT t FROM FeedInTariff t
        WHERE t.region = :region
          AND t.energySource = :source
          AND t.periodType = :period
          AND t.effectiveFrom <= :asOf
        ORDER BY t.effectiveFrom DESC
        """)
    List<FeedInTariff> findEffectiveOrdered(String region, EnergySource source, String period, LocalDate asOf, PageRequest page);

    default Optional<FeedInTariff> findEffective(String region, EnergySource source, String period, LocalDate asOf) {
        var rows = findEffectiveOrdered(region, source, period, asOf, PageRequest.of(0, 1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Admin UI 列表：按 (region, energy_source, period_type) 分组、effective_from 倒序，便于前端识别"当前生效"行。 */
    List<FeedInTariff> findAllByOrderByRegionAscEnergySourceAscPeriodTypeAscEffectiveFromDesc();

    /** 唯一键去重检测——和 V2.6.0 schema 上的 UNIQUE (region, energy_source, period_type, effective_from) 一致。 */
    boolean existsByRegionAndEnergySourceAndPeriodTypeAndEffectiveFrom(
            String region, EnergySource energySource, String periodType, LocalDate effectiveFrom);
}
