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

    /**
     * 找最近一次 SUCCESS 且其 [periodStart, periodEnd] 完全覆盖给定窗口的 run。
     * 用于 ems-billing 账单生成：BillPeriod 必须由完全覆盖它的 cost run 生成账单。
     */
    @Query("SELECT r FROM CostAllocationRun r " +
           "WHERE r.status = com.ems.cost.entity.RunStatus.SUCCESS " +
           "AND r.periodStart <= :start AND r.periodEnd >= :end " +
           "ORDER BY r.finishedAt DESC")
    List<CostAllocationRun> findLatestSuccessCovering(
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end);
}
