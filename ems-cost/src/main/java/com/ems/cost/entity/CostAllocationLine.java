package com.ems.cost.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "cost_allocation_line")
public class CostAllocationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "target_org_id", nullable = false)
    private Long targetOrgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "energy_type", nullable = false, length = 32)
    private EnergyTypeCode energyType;

    @Column(precision = 18, scale = 4, nullable = false)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(precision = 18, scale = 4, nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "sharp_quantity", precision = 18, scale = 4, nullable = false)
    private BigDecimal sharpQuantity = BigDecimal.ZERO;

    @Column(name = "peak_quantity", precision = 18, scale = 4, nullable = false)
    private BigDecimal peakQuantity = BigDecimal.ZERO;

    @Column(name = "flat_quantity", precision = 18, scale = 4, nullable = false)
    private BigDecimal flatQuantity = BigDecimal.ZERO;

    @Column(name = "valley_quantity", precision = 18, scale = 4, nullable = false)
    private BigDecimal valleyQuantity = BigDecimal.ZERO;

    @Column(name = "sharp_amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal sharpAmount = BigDecimal.ZERO;

    @Column(name = "peak_amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal peakAmount = BigDecimal.ZERO;

    @Column(name = "flat_amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal flatAmount = BigDecimal.ZERO;

    @Column(name = "valley_amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal valleyAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public Long getRuleId() { return ruleId; }
    public Long getTargetOrgId() { return targetOrgId; }
    public EnergyTypeCode getEnergyType() { return energyType; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getSharpQuantity() { return sharpQuantity; }
    public BigDecimal getPeakQuantity() { return peakQuantity; }
    public BigDecimal getFlatQuantity() { return flatQuantity; }
    public BigDecimal getValleyQuantity() { return valleyQuantity; }
    public BigDecimal getSharpAmount() { return sharpAmount; }
    public BigDecimal getPeakAmount() { return peakAmount; }
    public BigDecimal getFlatAmount() { return flatAmount; }
    public BigDecimal getValleyAmount() { return valleyAmount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setRunId(Long v) { this.runId = v; }
    public void setRuleId(Long v) { this.ruleId = v; }
    public void setTargetOrgId(Long v) { this.targetOrgId = v; }
    public void setEnergyType(EnergyTypeCode v) { this.energyType = v; }
    public void setQuantity(BigDecimal v) { this.quantity = v; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public void setSharpQuantity(BigDecimal v) { this.sharpQuantity = v; }
    public void setPeakQuantity(BigDecimal v) { this.peakQuantity = v; }
    public void setFlatQuantity(BigDecimal v) { this.flatQuantity = v; }
    public void setValleyQuantity(BigDecimal v) { this.valleyQuantity = v; }
    public void setSharpAmount(BigDecimal v) { this.sharpAmount = v; }
    public void setPeakAmount(BigDecimal v) { this.peakAmount = v; }
    public void setFlatAmount(BigDecimal v) { this.flatAmount = v; }
    public void setValleyAmount(BigDecimal v) { this.valleyAmount = v; }
}
