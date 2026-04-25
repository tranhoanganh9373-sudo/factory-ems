package com.ems.dashboard.support;

import com.ems.core.exception.ForbiddenException;
import com.ems.core.security.PermissionResolver;
import com.ems.meter.entity.EnergyType;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.repository.MeterRepository;
import com.ems.orgtree.service.OrgNodeService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public DashboardSupport(PermissionResolver permissions, OrgNodeService orgNodes,
                            MeterRepository meters, EnergyTypeRepository energyTypes) {
        this.permissions = permissions;
        this.orgNodes = orgNodes;
        this.meters = meters;
        this.energyTypes = energyTypes;
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
                    m.getEnabled()
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
            m.getEnabled()
        );
    }
}
