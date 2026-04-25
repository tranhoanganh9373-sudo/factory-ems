package com.ems.floorplan.repository;

import com.ems.floorplan.entity.FloorplanPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FloorplanPointRepository extends JpaRepository<FloorplanPoint, Long> {

    List<FloorplanPoint> findByFloorplanIdOrderByIdAsc(Long floorplanId);

    @Modifying
    @Query("DELETE FROM FloorplanPoint p WHERE p.floorplanId = :floorplanId")
    void deleteByFloorplanId(@Param("floorplanId") Long floorplanId);

    Optional<FloorplanPoint> findByFloorplanIdAndMeterId(Long floorplanId, Long meterId);
}
