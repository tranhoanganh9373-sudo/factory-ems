package com.ems.timeseries.rollup.repository;

import com.ems.timeseries.rollup.entity.RollupJobFailure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RollupJobFailureRepository extends JpaRepository<RollupJobFailure, Long> {

    @Query("""
        SELECT f FROM RollupJobFailure f
         WHERE f.granularity = :granularity
           AND f.bucketTs    = :bucketTs
           AND ((:meterId IS NULL AND f.meterId IS NULL) OR f.meterId = :meterId)
           AND f.abandoned = false
        """)
    Optional<RollupJobFailure> findActive(@Param("granularity") String granularity,
                                          @Param("bucketTs") OffsetDateTime bucketTs,
                                          @Param("meterId") Long meterId);

    @Query("""
        SELECT f FROM RollupJobFailure f
         WHERE f.abandoned = false
           AND f.nextRetryAt <= :now
         ORDER BY f.nextRetryAt ASC
        """)
    List<RollupJobFailure> findDueForRetry(@Param("now") OffsetDateTime now);
}
