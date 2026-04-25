package com.ems.meter.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import com.ems.meter.dto.*;
import com.ems.meter.entity.EnergyType;
import com.ems.meter.entity.Meter;
import com.ems.meter.entity.MeterTopology;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.repository.MeterRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.meter.service.MeterService;
import com.ems.orgtree.repository.OrgNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MeterServiceImpl implements MeterService {

    private final MeterRepository meters;
    private final MeterTopologyRepository topology;
    private final EnergyTypeRepository energyTypes;
    private final OrgNodeRepository orgNodes;

    public MeterServiceImpl(MeterRepository meters, MeterTopologyRepository topology,
                            EnergyTypeRepository energyTypes, OrgNodeRepository orgNodes) {
        this.meters = meters;
        this.topology = topology;
        this.energyTypes = energyTypes;
        this.orgNodes = orgNodes;
    }

    @Override
    @Transactional
    @Audited(action = "CREATE", resourceType = "METER", resourceIdExpr = "#result.id()")
    public MeterDTO create(CreateMeterReq req) {
        if (meters.existsByCode(req.code())) {
            throw new BusinessException(ErrorCode.CONFLICT, "测点编码已存在: " + req.code());
        }
        if (meters.existsByInfluxMeasurementAndInfluxTagKeyAndInfluxTagValue(
                req.influxMeasurement(), req.influxTagKey(), req.influxTagValue())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "InfluxDB tag 三元组已被占用: " + req.influxMeasurement() + "/"
                + req.influxTagKey() + "=" + req.influxTagValue());
        }
        EnergyType type = energyTypes.findById(req.energyTypeId())
            .orElseThrow(() -> new NotFoundException("EnergyType", req.energyTypeId()));
        if (!orgNodes.existsById(req.orgNodeId())) {
            throw new NotFoundException("OrgNode", req.orgNodeId());
        }

        Meter m = new Meter();
        m.setCode(req.code());
        m.setName(req.name());
        m.setEnergyTypeId(req.energyTypeId());
        m.setOrgNodeId(req.orgNodeId());
        m.setInfluxMeasurement(req.influxMeasurement());
        m.setInfluxTagKey(req.influxTagKey());
        m.setInfluxTagValue(req.influxTagValue());
        m.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        meters.save(m);

        return toDTO(m, type, null);
    }

    @Override
    @Transactional
    @Audited(action = "UPDATE", resourceType = "METER", resourceIdExpr = "#id")
    public MeterDTO update(Long id, UpdateMeterReq req) {
        Meter m = meters.findById(id).orElseThrow(() -> new NotFoundException("Meter", id));

        boolean tagChanged =
            !m.getInfluxMeasurement().equals(req.influxMeasurement())
            || !m.getInfluxTagKey().equals(req.influxTagKey())
            || !m.getInfluxTagValue().equals(req.influxTagValue());
        if (tagChanged && meters.existsByInfluxMeasurementAndInfluxTagKeyAndInfluxTagValue(
                req.influxMeasurement(), req.influxTagKey(), req.influxTagValue())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "InfluxDB tag 三元组已被占用: " + req.influxMeasurement() + "/"
                + req.influxTagKey() + "=" + req.influxTagValue());
        }

        EnergyType type = energyTypes.findById(req.energyTypeId())
            .orElseThrow(() -> new NotFoundException("EnergyType", req.energyTypeId()));
        if (!orgNodes.existsById(req.orgNodeId())) {
            throw new NotFoundException("OrgNode", req.orgNodeId());
        }

        m.setName(req.name());
        m.setEnergyTypeId(req.energyTypeId());
        m.setOrgNodeId(req.orgNodeId());
        m.setInfluxMeasurement(req.influxMeasurement());
        m.setInfluxTagKey(req.influxTagKey());
        m.setInfluxTagValue(req.influxTagValue());
        if (req.enabled() != null) m.setEnabled(req.enabled());
        meters.save(m);

        Long parentId = topology.findByChildMeterId(id).map(MeterTopology::getParentMeterId).orElse(null);
        return toDTO(m, type, parentId);
    }

    @Override
    @Transactional
    @Audited(action = "DELETE", resourceType = "METER", resourceIdExpr = "#id")
    public void delete(Long id) {
        Meter m = meters.findById(id).orElseThrow(() -> new NotFoundException("Meter", id));
        long children = topology.countByParentMeterId(id);
        if (children > 0) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC,
                "测点下仍有 " + children + " 个子测点，请先解绑");
        }
        topology.findByChildMeterId(id).ifPresent(topology::delete);
        meters.delete(m);
    }

    @Override
    @Transactional(readOnly = true)
    public MeterDTO getById(Long id) {
        Meter m = meters.findById(id).orElseThrow(() -> new NotFoundException("Meter", id));
        EnergyType type = energyTypes.findById(m.getEnergyTypeId()).orElse(null);
        Long parentId = topology.findByChildMeterId(id).map(MeterTopology::getParentMeterId).orElse(null);
        return toDTO(m, type, parentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MeterDTO> list(Long orgNodeId, Long energyTypeId, Boolean enabled) {
        List<Meter> all = meters.findAllByOrderByCodeAsc();
        Map<Long, EnergyType> typeMap = new HashMap<>();
        for (EnergyType t : energyTypes.findAll()) typeMap.put(t.getId(), t);

        Map<Long, Long> parentMap = new HashMap<>();
        for (MeterTopology t : topology.findAll()) parentMap.put(t.getChildMeterId(), t.getParentMeterId());

        return all.stream()
            .filter(m -> orgNodeId == null || orgNodeId.equals(m.getOrgNodeId()))
            .filter(m -> energyTypeId == null || energyTypeId.equals(m.getEnergyTypeId()))
            .filter(m -> enabled == null || enabled.equals(m.getEnabled()))
            .map(m -> toDTO(m, typeMap.get(m.getEnergyTypeId()), parentMap.get(m.getId())))
            .toList();
    }

    private MeterDTO toDTO(Meter m, EnergyType type, Long parentMeterId) {
        return new MeterDTO(
            m.getId(), m.getCode(), m.getName(),
            m.getEnergyTypeId(),
            type != null ? type.getCode() : null,
            type != null ? type.getName() : null,
            type != null ? type.getUnit() : null,
            m.getOrgNodeId(),
            m.getInfluxMeasurement(), m.getInfluxTagKey(), m.getInfluxTagValue(),
            m.getEnabled(), parentMeterId,
            m.getCreatedAt(), m.getUpdatedAt());
    }
}
