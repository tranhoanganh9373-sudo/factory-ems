package com.ems.auth.service.impl;

import com.ems.auth.repository.NodePermissionRepository;
import com.ems.auth.repository.UserRoleRepository;
import com.ems.auth.service.PermissionResolver;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class PermissionResolverImpl implements PermissionResolver {

    private final NodePermissionRepository perms;
    private final UserRoleRepository userRoles;

    public PermissionResolverImpl(NodePermissionRepository p, UserRoleRepository ur) {
        this.perms = p; this.userRoles = ur;
    }

    @Override
    public Set<Long> visibleNodeIds(Long userId) {
        if (userId == null) return Set.of();
        if (userRoles.findRoleCodesByUserId(userId).contains("ADMIN")) {
            return ALL_NODE_IDS_MARKER;
        }
        return perms.findVisibleNodeIds(userId);
    }

    @Override
    public boolean canAccess(Long userId, Long orgNodeId) {
        Set<Long> v = visibleNodeIds(userId);
        return v == ALL_NODE_IDS_MARKER || v.contains(orgNodeId);
    }

    @Override
    public boolean hasAllNodes(Set<Long> visible) {
        return visible == ALL_NODE_IDS_MARKER;
    }
}
