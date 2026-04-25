package com.ems.tariff.repository;

import com.ems.tariff.entity.TariffPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TariffPeriodRepository extends JpaRepository<TariffPeriod, Long> {

    List<TariffPeriod> findByPlanIdOrderByTimeStartAsc(Long planId);

    void deleteByPlanId(Long planId);
}
