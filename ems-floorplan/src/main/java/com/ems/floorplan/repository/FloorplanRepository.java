package com.ems.floorplan.repository;

import com.ems.floorplan.entity.Floorplan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FloorplanRepository extends JpaRepository<Floorplan, Long> {

    boolean existsByFilePath(String filePath);

    List<Floorplan> findAllByEnabledTrueOrderByIdDesc();

    List<Floorplan> findByOrgNodeIdAndEnabledTrueOrderByIdDesc(Long orgNodeId);
}
