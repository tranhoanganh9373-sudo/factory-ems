package com.ems.auth.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.auth.dto.*;
import com.ems.auth.entity.NodePermission;
import com.ems.auth.repository.NodePermissionRepository;
import com.ems.auth.repository.UserRepository;
import com.ems.auth.service.NodePermissionService;
import com.ems.core.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NodePermissionServiceImpl implements NodePermissionService {

    private final NodePermissionRepository perms;
    private final UserRepository users;

    public NodePermissionServiceImpl(NodePermissionRepository p, UserRepository u) {
        this.perms = p; this.users = u;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NodePermissionDTO> listByUser(Long userId) {
        return perms.findByUserId(userId).stream()
            .map(n -> new NodePermissionDTO(n.getId(), n.getUserId(), n.getOrgNodeId(),
                n.getScope(), n.getCreatedAt()))
            .toList();
    }

    @Override
    @Transactional
    @Audited(action = "CONFIG_CHANGE", resourceType = "NODE_PERMISSION",
             resourceIdExpr = "#userId", summaryExpr = "'grant node permission'")
    public NodePermissionDTO assign(Long userId, AssignNodePermissionReq req) {
        users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        NodePermission p = new NodePermission();
        p.setUserId(userId);
        p.setOrgNodeId(req.orgNodeId());
        p.setScope(req.scope());
        perms.save(p);
        return new NodePermissionDTO(p.getId(), p.getUserId(), p.getOrgNodeId(),
            p.getScope(), p.getCreatedAt());
    }

    @Override
    @Transactional
    @Audited(action = "CONFIG_CHANGE", resourceType = "NODE_PERMISSION",
             resourceIdExpr = "#permissionId", summaryExpr = "'revoke node permission'")
    public void revoke(Long permissionId) {
        perms.findById(permissionId).orElseThrow(() ->
            new NotFoundException("NodePermission", permissionId));
        perms.deleteById(permissionId);
    }
}
