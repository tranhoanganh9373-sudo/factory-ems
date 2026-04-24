package com.ems.orgtree.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "org_nodes")
public class OrgNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "node_type", nullable = false, length = 32)
    private String nodeType;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = createdAt != null ? createdAt : now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = OffsetDateTime.now(); }

    // getters/setters
    public Long getId() { return id; }
    public Long getParentId() { return parentId; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public String getNodeType() { return nodeType; }
    public Integer getSortOrder() { return sortOrder; }
    public Long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setParentId(Long v) { this.parentId = v; }
    public void setName(String v) { this.name = v; }
    public void setCode(String v) { this.code = v; }
    public void setNodeType(String v) { this.nodeType = v; }
    public void setSortOrder(Integer v) { this.sortOrder = v; }
}
