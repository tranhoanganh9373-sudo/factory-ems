package com.ems.dashboard.support;

import com.ems.core.exception.ForbiddenException;
import com.ems.core.security.PermissionResolver;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.EnergyType;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.Meter;
import com.ems.meter.entity.MeterRole;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.repository.MeterRepository;
import com.ems.meter.service.MeterTopologyService;
import com.ems.orgtree.service.OrgNodeService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 看板权限/范围 + 测点解析的统一入口。
 *  - 强制走 PermissionResolver.visibleNodeIds()
 *  - orgNodeId 校验 → ForbiddenException
 *  - findDescendantIds(orgNodeId) ∩ visibleNodeIds 得到最终节点集合
 *  - 一次性 join 测点 + 能源品类，得到所有 panel 共用的 MeterRecord 列表
 */
@Component
public class DashboardSupport {

    private final PermissionResolver permissions;
    private final OrgNodeService orgNodes;
    private final MeterRepository meters;
    private final EnergyTypeRepository energyTypes;
    private final MeterTopologyService topology;

    public DashboardSupport(PermissionResolver permissions, OrgNodeService orgNodes,
                            MeterRepository meters, EnergyTypeRepository energyTypes,
                            MeterTopologyService topology) {
        this.permissions = permissions;
        this.orgNodes = orgNodes;
        this.meters = meters;
        this.energyTypes = energyTypes;
        this.topology = topology;
    }

    /** 解析当前请求的可见测点。energyType 为空则不过滤品类。 */
    public List<MeterRecord> resolveMeters(Long orgNodeId, String energyType) {
        Long uid = permissions.currentUserId();
        if (uid == null) {
            throw new ForbiddenException("未登录或登录态无效");
        }
        Set<Long> visible = permissions.visibleNodeIds(uid);
        boolean admin = permissions.hasAllNodes(visible);

        // 1. 校验 orgNodeId
        if (orgNodeId != null && !admin && !visible.contains(orgNodeId)) {
            throw new ForbiddenException("无权访问该组织节点: " + orgNodeId);
        }

        // 2. 解析 nodeIds 范围
        Set<Long> nodeIds;
        if (orgNodeId != null) {
            List<Long> desc = orgNodes.findDescendantIds(orgNodeId);
            if (admin) {
                nodeIds = new HashSet<>(desc);
            } else {
                nodeIds = new HashSet<>(desc);
                nodeIds.retainAll(visible);
            }
        } else {
            // 不指定 orgNodeId：admin 看全部，普通用户看 visible 内全部
            nodeIds = admin ? null : new HashSet<>(visible);
        }

        // 3. 加载测点
        List<Meter> raw;
        if (nodeIds == null) {
            raw = meters.findAllByOrderByCodeAsc();
        } else if (nodeIds.isEmpty()) {
            return List.of();
        } else {
            raw = meters.findByOrgNodeIdIn(nodeIds);
        }

        // 4. enrich energyType
        Map<Long, EnergyType> typeMap = new HashMap<>();
        for (EnergyType t : energyTypes.findAll()) typeMap.put(t.getId(), t);

        return raw.stream()
            .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
            .map(m -> {
                EnergyType et = typeMap.get(m.getEnergyTypeId());
                return new MeterRecord(
                    m.getId(), m.getCode(), m.getName(),
                    m.getOrgNodeId(), m.getInfluxTagValue(),
                    m.getEnergyTypeId(),
                    et != null ? et.getCode() : null,
                    et != null ? et.getUnit() : null,
                    m.getEnabled(),
                    m.getValueKind(),
                    m.getRole(),
                    m.getEnergySource(),
                    m.getFlowDirection()
                );
            })
            .filter(r -> energyType == null || energyType.isBlank() || energyType.equalsIgnoreCase(r.energyTypeCode()))
            .toList();
    }

    /** 加载单个测点 + 权限校验。失败返回 ForbiddenException 或 NotFound。 */
    public MeterRecord resolveOneMeter(Long meterId) {
        Long uid = permissions.currentUserId();
        if (uid == null) throw new ForbiddenException("未登录或登录态无效");
        Set<Long> visible = permissions.visibleNodeIds(uid);
        boolean admin = permissions.hasAllNodes(visible);

        Meter m = meters.findById(meterId)
            .orElseThrow(() -> new com.ems.core.exception.NotFoundException("Meter", meterId));
        if (!admin && !visible.contains(m.getOrgNodeId())) {
            throw new ForbiddenException("无权访问该测点");
        }
        EnergyType et = energyTypes.findById(m.getEnergyTypeId()).orElse(null);
        return new MeterRecord(
            m.getId(), m.getCode(), m.getName(),
            m.getOrgNodeId(), m.getInfluxTagValue(),
            m.getEnergyTypeId(),
            et != null ? et.getCode() : null,
            et != null ? et.getUnit() : null,
            m.getEnabled(),
            m.getValueKind(),
            m.getRole(),
            m.getEnergySource(),
            m.getFlowDirection()
        );
    }

    /**
     * 拓扑根聚合：保留 visible 集合中的"根"——一个表是根 iff
     * (a) 它在 meter_topology 中没有父表，OR (b) 父表不在 visible 集合内（被 org / energyType 过滤掉了）。
     *
     * 用于 KPI / 实时曲线 / 能耗构成 三处的总量计算，避免父表 + 子表读数被同时累加。
     */
    public List<MeterRecord> filterToVisibleRoots(Collection<MeterRecord> visible) {
        if (visible == null || visible.isEmpty()) return List.of();
        Map<Long, Long> parentByChild = topology.parentMap();
        Set<Long> visibleIds = visible.stream().map(MeterRecord::meterId).collect(Collectors.toSet());
        List<MeterRecord> out = new ArrayList<>();
        for (MeterRecord m : visible) {
            Long parent = parentByChild.get(m.meterId());
            if (parent == null || !visibleIds.contains(parent)) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * 叶子聚合：visible 集合中没有任何子表也在 visible 集合内的表。用于 TopN 默认排名。
     * 不返回根、也不返回中间层；纯叶子。
     */
    public List<MeterRecord> filterToVisibleLeaves(Collection<MeterRecord> visible) {
        if (visible == null || visible.isEmpty()) return List.of();
        Map<Long, Long> parentByChild = topology.parentMap();
        Set<Long> visibleIds = visible.stream().map(MeterRecord::meterId).collect(Collectors.toSet());
        // parents within visible set
        Set<Long> parentsInVisible = new HashSet<>();
        for (Map.Entry<Long, Long> e : parentByChild.entrySet()) {
            if (visibleIds.contains(e.getKey()) && visibleIds.contains(e.getValue())) {
                parentsInVisible.add(e.getValue());
            }
        }
        List<MeterRecord> out = new ArrayList<>();
        for (MeterRecord m : visible) {
            if (!parentsInVisible.contains(m.meterId())) out.add(m);
        }
        return out;
    }
}
