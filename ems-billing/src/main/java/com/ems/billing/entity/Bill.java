package com.ems.billing.entity;

import com.ems.cost.entity.EnergyTypeCode;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 账单一行 = (period, org_node, energy_type) 的聚合。run_id 指向当时引用的 SUCCESS 分摊批次。
 * unit_cost / unit_intensity 在 production_qty=0 或 NULL 时保持 NULL（前端显示 "—"）。
 */
@Entity
@Table(
    name = "bill",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_bill_period_org_energy",
        columnNames = {"period_id", "org_node_id", "energy_type"})
)
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_id", nullable = false)
    private Long periodId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "org_node_id", nullable = false)
    private Long orgNodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "energy_type", nullable = false, length = 32)
    private EnergyTypeCode energyType;

    @Column(precision = 18, scale = 4, nullable = false)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(precision = 18, scale = 4, nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "sharp_amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal sharpAmount = BigDecimal.ZERO;

    @Column(name = "peak_amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal peakAmount = BigDecimal.ZERO;

    @Column(name = "flat_amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal flatAmount = BigDecimal.ZERO;

    @Column(name = "valley_amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal valleyAmount = BigDecimal.ZERO;

    @Column(name = "production_qty", precision = 18, scale = 4)
    private BigDecimal productionQty;

    @Column(name = "unit_cost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "unit_intensity", precision = 18, scale = 6)
    private BigDecimal unitIntensity;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getPeriodId() { return periodId; }
    public Long getRunId() { return runId; }
    public Long getOrgNodeId() { return orgNodeId; }
    public EnergyTypeCode getEnergyType() { return energyType; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getSharpAmount() { return sharpAmount; }
    public BigDecimal getPeakAmount() { return peakAmount; }
    public BigDecimal getFlatAmount() { return flatAmount; }
    public BigDecimal getValleyAmount() { return valleyAmount; }
    public BigDecimal getProductionQty() { return productionQty; }
    public BigDecimal getUnitCost() { return unitCost; }
    public BigDecimal getUnitIntensity() { return unitIntensity; }
    public Long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long v) { this.id = v; }
    public void setPeriodId(Long v) { this.periodId = v; }
    public void setRunId(Long v) { this.runId = v; }
    public void setOrgNodeId(Long v) { this.orgNodeId = v; }
    public void setEnergyType(EnergyTypeCode v) { this.energyType = v; }
    public void setQuantity(BigDecimal v) { this.quantity = v; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public void setSharpAmount(BigDecimal v) { this.sharpAmount = v; }
    public void setPeakAmount(BigDecimal v) { this.peakAmount = v; }
    public void setFlatAmount(BigDecimal v) { this.flatAmount = v; }
    public void setValleyAmount(BigDecimal v) { this.valleyAmount = v; }
    public void setProductionQty(BigDecimal v) { this.productionQty = v; }
    public void setUnitCost(BigDecimal v) { this.unitCost = v; }
    public void setUnitIntensity(BigDecimal v) { this.unitIntensity = v; }
}
