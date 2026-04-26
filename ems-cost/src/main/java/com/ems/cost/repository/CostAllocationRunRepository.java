package com.ems.cost.repository;

import com.ems.cost.entity.CostAllocationRun;
import com.ems.cost.entity.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface CostAllocationRunRepository extends JpaRepository<CostAllocationRun, Long> {

    @Query("SELECT r FROM CostAllocationRun r " +
           "WHERE r.periodStart = :periodStart AND r.periodEnd = :periodEnd " +
           "AND r.status = com.ems.cost.entity.RunStatus.SUCCESS")
    Optional<CostAllocationRun> findSuccessByPeriod(
            @Param("periodStart") OffsetDateTime periodStart,
            @Param("periodEnd") OffsetDateTime periodEnd);

    List<CostAllocationRun> findByStatusOrderByCreatedAtDesc(RunStatus status);

    @Query("SELECT r FROM CostAllocationRun r " +
           "WHERE r.periodStart >= :from AND r.periodEnd <= :to " +
           "ORDER BY r.createdAt DESC")
    List<CostAllocationRun> findByPeriodRange(
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    @Modifying
    @Query("UPDATE CostAllocationRun r SET r.status = com.ems.cost.entity.RunStatus.SUPERSEDED " +
           "WHERE r.periodStart = :periodStart AND r.periodEnd = :periodEnd " +
           "AND r.status = com.ems.cost.entity.RunStatus.SUCCESS AND r.id <> :exceptId")
    int markPriorSuccessSuperseded(
            @Param("periodStart") OffsetDateTime periodStart,
            @Param("periodEnd") OffsetDateTime periodEnd,
            @Param("exceptId") Long exceptId);
}
