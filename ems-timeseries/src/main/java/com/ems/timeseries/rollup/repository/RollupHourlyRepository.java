package com.ems.timeseries.rollup.repository;

import com.ems.timeseries.rollup.entity.RollupHourly;
import com.ems.timeseries.rollup.entity.RollupHourlyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RollupHourlyRepository extends JpaRepository<RollupHourly, RollupHourlyId> {

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO ts_rollup_hourly
            (meter_id, org_node_id, hour_ts, sum_value, avg_value, max_value, min_value, count, updated_at)
        VALUES (:meterId, :orgNodeId, :hourTs, :sum, :avg, :max, :min, :count, now())
        ON CONFLICT (meter_id, hour_ts) DO UPDATE SET
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
               @Param("hourTs") OffsetDateTime hourTs,
               @Param("sum") BigDecimal sum,
               @Param("avg") BigDecimal avg,
               @Param("max") BigDecimal max,
               @Param("min") BigDecimal min,
               @Param("count") Integer count);

    @Query("""
        SELECT r FROM RollupHourly r
         WHERE r.meterId IN :meterIds
           AND r.hourTs >= :start AND r.hourTs < :end
         ORDER BY r.meterId, r.hourTs
        """)
    List<RollupHourly> findInRange(@Param("meterIds") Collection<Long> meterIds,
                                   @Param("start") OffsetDateTime start,
                                   @Param("end") OffsetDateTime end);

    @Query("SELECT MAX(r.hourTs) FROM RollupHourly r")
    Optional<OffsetDateTime> findMaxHour();
}
