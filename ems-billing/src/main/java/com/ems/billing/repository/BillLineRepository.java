package com.ems.billing.repository;

import com.ems.billing.entity.BillLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillLineRepository extends JpaRepository<BillLine, Long> {

    List<BillLine> findByBillId(Long billId);

    List<BillLine> findByBillIdIn(List<Long> billIds);
}
