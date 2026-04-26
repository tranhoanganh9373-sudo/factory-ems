package com.ems.cost.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "cost_allocation_run")
public class CostAllocationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_start", nullable = false)
    private OffsetDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private OffsetDateTime periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status;

    @Column(name = "algorithm_version", nullable = false, length = 16)
    private String algorithmVersion = "v1";

    @Column(name = "total_amount", precision = 18, scale = 4)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "rule_ids", columnDefinition = "bigint[]")
    private Long[] ruleIds;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public OffsetDateTime getPeriodStart() { return periodStart; }
    public OffsetDateTime getPeriodEnd() { return periodEnd; }
    public RunStatus getStatus() { return status; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public Long[] getRuleIds() { return ruleIds; }
    public Long getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public String getErrorMessage() { return errorMessage; }

    public void setId(Long v) { this.id = v; }
    public void setPeriodStart(OffsetDateTime v) { this.periodStart = v; }
    public void setPeriodEnd(OffsetDateTime v) { this.periodEnd = v; }
    public void setStatus(RunStatus v) { this.status = v; }
    public void setAlgorithmVersion(String v) { this.algorithmVersion = v; }
    public void setTotalAmount(BigDecimal v) { this.totalAmount = v; }
    public void setRuleIds(Long[] v) { this.ruleIds = v; }
    public void setCreatedBy(Long v) { this.createdBy = v; }
    public void setFinishedAt(OffsetDateTime v) { this.finishedAt = v; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
}
