package com.ems.timeseries.rollup.repository;

import com.ems.timeseries.rollup.entity.RollupMonthly;
import com.ems.timeseries.rollup.entity.RollupMonthlyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RollupMonthlyRepository extends JpaRepository<RollupMonthly, RollupMonthlyId> {

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO ts_rollup_monthly
            (meter_id, org_node_id, year_month, sum_value, avg_value, max_value, min_value, count, updated_at)
        VALUES (:meterId, :orgNodeId, :yearMonth, :sum, :avg, :max, :min, :count, now())
        ON CONFLICT (meter_id, year_month) DO UPDATE SET
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
               @Param("yearMonth") String yearMonth,
               @Param("sum") BigDecimal sum,
               @Param("avg") BigDecimal avg,
               @Param("max") BigDecimal max,
               @Param("min") BigDecimal min,
               @Param("count") Integer count);

    @Query("""
        SELECT r FROM RollupMonthly r
         WHERE r.meterId IN :meterIds
           AND r.yearMonth >= :start AND r.yearMonth < :end
         ORDER BY r.meterId, r.yearMonth
        """)
    List<RollupMonthly> findInRange(@Param("meterIds") Collection<Long> meterIds,
                                    @Param("start") String start,
                                    @Param("end") String end);

    @Query("SELECT MAX(r.yearMonth) FROM RollupMonthly r")
    Optional<String> findMaxYearMonth();
}
