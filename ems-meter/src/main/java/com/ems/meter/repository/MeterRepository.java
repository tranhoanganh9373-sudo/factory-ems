package com.ems.meter.repository;

import com.ems.meter.entity.Meter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MeterRepository extends JpaRepository<Meter, Long> {

    Optional<Meter> findByCode(String code);
    boolean existsByCode(String code);

    /**
     * 通过 (channel, point key) 反查 meter。
     * 约定：channel 配置中 {@code points[].key} 等于该 meter 的 {@code code}。
     * 由 {@code InfluxSampleWriter} 用于把 collector Sample 路由到正确的 meter 并写 InfluxDB。
     */
    Optional<Meter> findByChannelIdAndCode(Long channelId, String code);

    boolean existsByInfluxMeasurementAndInfluxTagKeyAndInfluxTagValue(
        String measurement, String tagKey, String tagValue);

    long countByEnergyTypeId(Long energyTypeId);
    long countByOrgNodeId(Long orgNodeId);

    List<Meter> findAllByOrderByCodeAsc();

    @Query("SELECT m FROM Meter m WHERE m.orgNodeId IN :nodeIds")
    List<Meter> findByOrgNodeIdIn(@Param("nodeIds") Collection<Long> nodeIds);

    @Query("SELECT m FROM Meter m WHERE m.orgNodeId IN :nodeIds AND m.energyTypeId = :energyTypeId")
    List<Meter> findByOrgNodeIdInAndEnergyTypeId(
        @Param("nodeIds") Collection<Long> nodeIds,
        @Param("energyTypeId") Long energyTypeId);
}
