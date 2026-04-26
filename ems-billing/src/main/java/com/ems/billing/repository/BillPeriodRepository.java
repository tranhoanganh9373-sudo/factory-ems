package com.ems.billing.repository;

import com.ems.billing.entity.BillPeriod;
import com.ems.billing.entity.BillPeriodStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillPeriodRepository extends JpaRepository<BillPeriod, Long> {

    Optional<BillPeriod> findByYearMonth(String yearMonth);

    List<BillPeriod> findByStatusOrderByYearMonthDesc(BillPeriodStatus status);

    List<BillPeriod> findAllByOrderByYearMonthDesc();
}
