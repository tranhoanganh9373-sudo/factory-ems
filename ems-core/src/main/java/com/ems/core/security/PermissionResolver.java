package com.ems.core.security;

import java.util.Set;

public interface PermissionResolver {
    /** Sentinel: ADMIN user has all nodes */
    Set<Long> ALL_NODE_IDS_MARKER = Set.of(-1L);

    Set<Long> visibleNodeIds(Long userId);

    boolean canAccess(Long userId, Long orgNodeId);

    boolean hasAllNodes(Set<Long> visible);

    /**
     * Returns the userId of the principal in the current SecurityContext, or null if none.
     * Lets callers in non-auth modules avoid depending on AuthUser directly.
     */
    Long currentUserId();
}
