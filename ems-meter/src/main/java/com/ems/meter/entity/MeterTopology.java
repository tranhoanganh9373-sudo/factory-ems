package com.ems.meter.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "meter_topology")
public class MeterTopology {

    @Id
    @Column(name = "child_meter_id")
    private Long childMeterId;

    @Column(name = "parent_meter_id", nullable = false)
    private Long parentMeterId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getChildMeterId() { return childMeterId; }
    public Long getParentMeterId() { return parentMeterId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setChildMeterId(Long v) { this.childMeterId = v; }
    public void setParentMeterId(Long v) { this.parentMeterId = v; }
}
