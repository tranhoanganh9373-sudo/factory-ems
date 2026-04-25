package com.ems.meter.repository;

import com.ems.meter.entity.EnergyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnergyTypeRepository extends JpaRepository<EnergyType, Long> {
    List<EnergyType> findAllByOrderBySortOrderAscIdAsc();
    Optional<EnergyType> findByCode(String code);
}
