package com.ems.timeseries.rollup.repository;

import com.ems.timeseries.rollup.entity.RollupDaily;
import com.ems.timeseries.rollup.entity.RollupDailyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RollupDailyRepository extends JpaRepository<RollupDaily, RollupDailyId> {

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO ts_rollup_daily
            (meter_id, org_node_id, day_date, sum_value, avg_value, max_value, min_value, count, updated_at)
        VALUES (:meterId, :orgNodeId, :dayDate, :sum, :avg, :max, :min, :count, now())
        ON CONFLICT (meter_id, day_date) DO UPDATE SET
            org_node_id = EXCLUDED.org_node_id,
            sum_value   = EXCLUDED.sum_value,
            avg_value   = EXCLUDED.avg_value,
            max_value   = EXCLUDED.max_value,
            min_value   = EXCLUDED.min_value,
            count       = EXCLUDED.count,
            updated_at  = now()
        """, nativeQuery = true)
    int upsert(@Param("meterId") Long meterId,
               @Param("orgNodeId") Long orgNodeId,
               @Param("dayDate") LocalDate dayDate,
               @Param("sum") BigDecimal sum,
               @Param("avg") BigDecimal avg,
               @Param("max") BigDecimal max,
               @Param("min") BigDecimal min,
               @Param("count") Integer count);

    @Query("""
        SELECT r FROM RollupDaily r
         WHERE r.meterId IN :meterIds
           AND r.dayDate >= :start AND r.dayDate < :end
         ORDER BY r.meterId, r.dayDate
        """)
    List<RollupDaily> findInRange(@Param("meterIds") Collection<Long> meterIds,
                                  @Param("start") LocalDate start,
                                  @Param("end") LocalDate end);

    @Query("SELECT MAX(r.dayDate) FROM RollupDaily r")
    Optional<LocalDate> findMaxDay();
}
