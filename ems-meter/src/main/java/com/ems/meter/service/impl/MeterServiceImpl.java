package com.ems.meter.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.core.constant.ErrorCode;
import com.ems.core.constant.ValueKind;
import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import com.ems.meter.dto.*;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.EnergyType;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.Meter;
import com.ems.meter.entity.MeterRole;
import com.ems.meter.entity.MeterTopology;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.repository.MeterRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.meter.service.MeterService;
import com.ems.orgtree.repository.OrgNodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MeterServiceImpl implements MeterService {

    /**
     * InfluxDB tag key 由 {@code FluxQueryBuilder} 硬编码读取（{@code r.meter_code}），
     * 因此写入端也固定为 {@code "meter_code"}。三列在 DB 里保留为 NOT NULL，
     * 但用户不再感知，由 service 在 save 时强制写入约定值，避免读写键不一致。
     */
    private static final String INFLUX_TAG_KEY = "meter_code";

    private final MeterRepository meters;
    private final MeterTopologyRepository topology;
    private final EnergyTypeRepository energyTypes;
    private final OrgNodeRepository orgNodes;
    private final String influxMeasurement;

    public MeterServiceImpl(MeterRepository meters, MeterTopologyRepository topology,
                            EnergyTypeRepository energyTypes, OrgNodeRepository orgNodes,
                            @Value("${ems.influx.measurement:energy_reading}") String influxMeasurement) {
        this.meters = meters;
        this.topology = topology;
        this.energyTypes = energyTypes;
        this.orgNodes = orgNodes;
        this.influxMeasurement = influxMeasurement;
    }

    @Override
    @Transactional
    @Audited(action = "CREATE", resourceType = "METER", resourceIdExpr = "#result.id()")
    public MeterDTO create(CreateMeterReq req) {
        if (meters.existsByCode(req.code())) {
            throw new BusinessException(ErrorCode.CONFLICT, "测点编码已存在: " + req.code());
        }
        EnergyType type = energyTypes.findById(req.energyTypeId())
            .orElseThrow(() -> new NotFoundException("EnergyType", req.energyTypeId()));
        if (!orgNodes.existsById(req.orgNodeId())) {
            throw new NotFoundException("OrgNode", req.orgNodeId());
        }
        String channelPointKey = normalizeBlank(req.channelPointKey());
        validateChannelPair(req.channelId(), channelPointKey);
        if (channelPointKey != null
            && meters.findByChannelIdAndChannelPointKey(req.channelId(), channelPointKey).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "该通道下已有测点绑定到 \"" + channelPointKey + "\"，请换一个测点 key");
        }

        Meter m = new Meter();
        m.setCode(req.code());
        m.setName(req.name());
        m.setEnergyTypeId(req.energyTypeId());
        m.setOrgNodeId(req.orgNodeId());
        // InfluxDB 三字段强制使用约定值，避免与读路径错位（详见 INFLUX_TAG_KEY javadoc）
        m.setInfluxMeasurement(influxMeasurement);
        m.setInfluxTagKey(INFLUX_TAG_KEY);
        m.setInfluxTagValue(req.code());
        m.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        m.setChannelId(req.channelId());
        m.setChannelPointKey(channelPointKey);
        m.setValueKind(req.valueKind() == null ? ValueKind.INTERVAL_DELTA : req.valueKind());
        m.setRole(req.role() == null ? MeterRole.CONSUME : req.role());
        m.setEnergySource(req.energySource() == null ? EnergySource.GRID : req.energySource());
        m.setFlowDirection(req.flowDirection() == null ? FlowDirection.IMPORT : req.flowDirection());
        meters.save(m);

        return toDTO(m, type, null);
    }

    @Override
    @Transactional
    @Audited(action = "UPDATE", resourceType = "METER", resourceIdExpr = "#id")
    public MeterDTO update(Long id, UpdateMeterReq req) {
        Meter m = meters.findById(id).orElseThrow(() -> new NotFoundException("Meter", id));

        boolean codeChanged = !m.getCode().equals(req.code());
        if (codeChanged && meters.existsByCode(req.code())) {
            throw new BusinessException(ErrorCode.CONFLICT, "测点编码已存在: " + req.code());
        }

        EnergyType type = energyTypes.findById(req.energyTypeId())
            .orElseThrow(() -> new NotFoundException("EnergyType", req.energyTypeId()));
        if (!orgNodes.existsById(req.orgNodeId())) {
            throw new NotFoundException("OrgNode", req.orgNodeId());
        }

        String channelPointKey = normalizeBlank(req.channelPointKey());
        validateChannelPair(req.channelId(), channelPointKey);
        if (channelPointKey != null) {
            // 同一 channel 下同一 pointKey 唯一；本 meter 自己保留旧值不算冲突
            meters.findByChannelIdAndChannelPointKey(req.channelId(), channelPointKey)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BusinessException(ErrorCode.CONFLICT,
                        "该通道下已有测点绑定到 \"" + channelPointKey + "\"，请换一个测点 key");
                });
        }

        m.setCode(req.code());
        m.setName(req.name());
        m.setEnergyTypeId(req.energyTypeId());
        m.setOrgNodeId(req.orgNodeId());
        // 强制约定值：旧脏数据（如 SMOKE-M1 用了 measurement="energy"/tagKey="meter"）会被规范化
        m.setInfluxMeasurement(influxMeasurement);
        m.setInfluxTagKey(INFLUX_TAG_KEY);
        m.setInfluxTagValue(req.code());
        if (req.enabled() != null) m.setEnabled(req.enabled());
        m.setChannelId(req.channelId());
        m.setChannelPointKey(channelPointKey);
        if (req.valueKind() != null) m.setValueKind(req.valueKind());
        if (req.role() != null) m.setRole(req.role());
        if (req.energySource() != null) m.setEnergySource(req.energySource());
        if (req.flowDirection() != null) m.setFlowDirection(req.flowDirection());
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
            m.getEnabled(), m.getChannelId(), m.getChannelPointKey(), parentMeterId,
            m.getValueKind(),
            m.getCreatedAt(), m.getUpdatedAt());
    }

    private static String normalizeBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 镜像 V2.3.2 DB CHECK 约束：channelId 和 channelPointKey 必须同进同退。
     * service 层先 fail-fast，把 SQL 层错误变成可读的业务错误。
     */
    private static void validateChannelPair(Long channelId, String channelPointKey) {
        if ((channelId == null) != (channelPointKey == null)) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC,
                "channelId 与 channelPointKey 必须同时设置或同时为空");
        }
    }
}
