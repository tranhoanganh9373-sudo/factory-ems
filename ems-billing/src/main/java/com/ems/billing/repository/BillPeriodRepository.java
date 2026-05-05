package com.ems.billing.repository;

import com.ems.billing.entity.BillPeriod;
import com.ems.billing.entity.BillPeriodStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface BillPeriodRepository extends JpaRepository<BillPeriod, Long> {

    Optional<BillPeriod> findByYearMonth(String yearMonth);

    List<BillPeriod> findByStatusOrderByYearMonthDesc(BillPeriodStatus status);

    List<BillPeriod> findAllByOrderByYearMonthDesc();

    @Query("SELECT p FROM BillPeriod p WHERE p.periodStart < :end AND p.periodEnd > :start")
    List<BillPeriod> findOverlapping(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);
}
