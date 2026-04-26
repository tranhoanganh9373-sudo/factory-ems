package com.ems.billing.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 账单分摊来源明细 — 解释"这 ¥1234 是怎么来的"。
 * bill 删除时级联清理（重写策略：DELETE bill -> CASCADE bill_line -> 重新 INSERT）。
 */
@Entity
@Table(name = "bill_line")
public class BillLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bill_id", nullable = false)
    private Long billId;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "source_label", nullable = false, length = 128)
    private String sourceLabel;

    @Column(precision = 18, scale = 4, nullable = false)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(precision = 18, scale = 4, nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getBillId() { return billId; }
    public Long getRuleId() { return ruleId; }
    public String getSourceLabel() { return sourceLabel; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getAmount() { return amount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setBillId(Long v) { this.billId = v; }
    public void setRuleId(Long v) { this.ruleId = v; }
    public void setSourceLabel(String v) { this.sourceLabel = v; }
    public void setQuantity(BigDecimal v) { this.quantity = v; }
    public void setAmount(BigDecimal v) { this.amount = v; }
}
