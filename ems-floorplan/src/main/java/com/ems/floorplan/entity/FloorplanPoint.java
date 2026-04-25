package com.ems.floorplan.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "floorplan_points")
public class FloorplanPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "floorplan_id", nullable = false)
    private Long floorplanId;

    @Column(name = "meter_id", nullable = false)
    private Long meterId;

    @Column(name = "x_ratio", nullable = false, precision = 7, scale = 6)
    private BigDecimal xRatio;

    @Column(name = "y_ratio", nullable = false, precision = 7, scale = 6)
    private BigDecimal yRatio;

    @Column(length = 64)
    private String label;

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

    public Long getId() { return id; }
    public Long getFloorplanId() { return floorplanId; }
    public Long getMeterId() { return meterId; }
    public BigDecimal getXRatio() { return xRatio; }
    public BigDecimal getYRatio() { return yRatio; }
    public String getLabel() { return label; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setFloorplanId(Long v) { this.floorplanId = v; }
    public void setMeterId(Long v) { this.meterId = v; }
    public void setXRatio(BigDecimal v) { this.xRatio = v; }
    public void setYRatio(BigDecimal v) { this.yRatio = v; }
    public void setLabel(String v) { this.label = v; }
}
