package com.ems.production.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "production_entries")
public class ProductionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_node_id", nullable = false)
    private Long orgNodeId;

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "product_code", nullable = false, length = 64)
    private String productCode;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(nullable = false, length = 16)
    private String unit;

    @Column(length = 255)
    private String remark;

    @Column(name = "created_by")
    private Long createdBy;

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
    public Long getOrgNodeId() { return orgNodeId; }
    public Long getShiftId() { return shiftId; }
    public LocalDate getEntryDate() { return entryDate; }
    public String getProductCode() { return productCode; }
    public BigDecimal getQuantity() { return quantity; }
    public String getUnit() { return unit; }
    public String getRemark() { return remark; }
    public Long getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setOrgNodeId(Long v) { this.orgNodeId = v; }
    public void setShiftId(Long v) { this.shiftId = v; }
    public void setEntryDate(LocalDate v) { this.entryDate = v; }
    public void setProductCode(String v) { this.productCode = v; }
    public void setQuantity(BigDecimal v) { this.quantity = v; }
    public void setUnit(String v) { this.unit = v; }
    public void setRemark(String v) { this.remark = v; }
    public void setCreatedBy(Long v) { this.createdBy = v; }
}
