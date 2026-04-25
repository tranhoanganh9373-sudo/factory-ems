package com.ems.meter.repository;

import com.ems.meter.entity.MeterTopology;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MeterTopologyRepository extends JpaRepository<MeterTopology, Long> {
    Optional<MeterTopology> findByChildMeterId(Long childMeterId);
    List<MeterTopology> findByParentMeterId(Long parentMeterId);
    long countByParentMeterId(Long parentMeterId);
    void deleteByParentMeterId(Long parentMeterId);
}
