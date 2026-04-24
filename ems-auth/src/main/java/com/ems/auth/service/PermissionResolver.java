package com.ems.auth.service;

import java.util.Set;

public interface PermissionResolver {
    /** Sentinel: ADMIN user has all nodes */
    Set<Long> ALL_NODE_IDS_MARKER = Set.of(-1L);

    Set<Long> visibleNodeIds(Long userId);
    boolean   canAccess(Long userId, Long orgNodeId);
    boolean   hasAllNodes(Set<Long> visible);
}
