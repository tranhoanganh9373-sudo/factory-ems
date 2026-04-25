package com.ems.production.repository;

import com.ems.production.entity.Shift;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    boolean existsByCode(String code);

    List<Shift> findAllByEnabledTrueOrderBySortOrderAscIdAsc();

    List<Shift> findAllByOrderBySortOrderAscIdAsc();
}
