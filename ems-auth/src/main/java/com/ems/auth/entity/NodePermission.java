package com.ems.auth.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "node_permissions")
public class NodePermission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "org_node_id", nullable = false) private Long orgNodeId;
    @Column(nullable = false, length = 16) private String scope;  // SUBTREE | NODE_ONLY
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getOrgNodeId() { return orgNodeId; }
    public String getScope() { return scope; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setUserId(Long v) { this.userId = v; }
    public void setOrgNodeId(Long v) { this.orgNodeId = v; }
    public void setScope(String v) { this.scope = v; }
}
