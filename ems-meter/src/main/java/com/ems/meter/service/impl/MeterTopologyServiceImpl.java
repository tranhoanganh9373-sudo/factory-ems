package com.ems.meter.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import com.ems.meter.dto.BindParentMeterReq;
import com.ems.meter.dto.MeterTopologyEdgeDTO;
import com.ems.meter.entity.MeterTopology;
import com.ems.meter.repository.MeterRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.meter.service.MeterTopologyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class MeterTopologyServiceImpl implements MeterTopologyService {

    private final MeterTopologyRepository topology;
    private final MeterRepository meters;

    public MeterTopologyServiceImpl(MeterTopologyRepository topology, MeterRepository meters) {
        this.topology = topology;
        this.meters = meters;
    }

    @Override
    @Transactional
    @Audited(action = "BIND", resourceType = "METER_TOPOLOGY", resourceIdExpr = "#childMeterId")
    public void bind(Long childMeterId, BindParentMeterReq req) {
        Long parentId = req.parentMeterId();
        if (Objects.equals(childMeterId, parentId)) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC, "测点不能挂在自身下");
        }
        if (!meters.existsById(childMeterId)) throw new NotFoundException("Meter", childMeterId);
        if (!meters.existsById(parentId)) throw new NotFoundException("Meter", parentId);

        // Cycle detection: walk up from candidate parent; if we hit child, abort.
        Map<Long, Long> edges = new HashMap<>();
        for (MeterTopology t : topology.findAll()) edges.put(t.getChildMeterId(), t.getParentMeterId());
        Long cursor = parentId;
        Set<Long> seen = new HashSet<>();
        while (cursor != null) {
            if (cursor.equals(childMeterId)) {
                throw new BusinessException(ErrorCode.BIZ_GENERIC, "不能形成环：候选父测点是自身的后代");
            }
            if (!seen.add(cursor)) break;  // safety against pre-existing cycle
            cursor = edges.get(cursor);
        }

        MeterTopology row = topology.findByChildMeterId(childMeterId).orElseGet(MeterTopology::new);
        row.setChildMeterId(childMeterId);
        row.setParentMeterId(parentId);
        topology.save(row);
    }

    @Override
    @Transactional
    @Audited(action = "UNBIND", resourceType = "METER_TOPOLOGY", resourceIdExpr = "#childMeterId")
    public void unbind(Long childMeterId) {
        topology.findByChildMeterId(childMeterId).ifPresent(topology::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MeterTopologyEdgeDTO> listAll() {
        return topology.findAll().stream()
            .map(t -> new MeterTopologyEdgeDTO(t.getChildMeterId(), t.getParentMeterId()))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> parentMap() {
        Map<Long, Long> out = new HashMap<>();
        for (MeterTopology t : topology.findAll()) {
            out.put(t.getChildMeterId(), t.getParentMeterId());
        }
        return out;
    }
}
