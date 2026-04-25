package com.ems.tariff.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tariff_periods")
public class TariffPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "period_type", nullable = false, length = 8)
    private String periodType;

    @Column(name = "time_start", nullable = false)
    private LocalTime timeStart;

    @Column(name = "time_end", nullable = false)
    private LocalTime timeEnd;

    @Column(name = "price_per_unit", nullable = false, precision = 12, scale = 4)
    private BigDecimal pricePerUnit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getPlanId() { return planId; }
    public String getPeriodType() { return periodType; }
    public LocalTime getTimeStart() { return timeStart; }
    public LocalTime getTimeEnd() { return timeEnd; }
    public BigDecimal getPricePerUnit() { return pricePerUnit; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setPlanId(Long v) { this.planId = v; }
    public void setPeriodType(String v) { this.periodType = v; }
    public void setTimeStart(LocalTime v) { this.timeStart = v; }
    public void setTimeEnd(LocalTime v) { this.timeEnd = v; }
    public void setPricePerUnit(BigDecimal v) { this.pricePerUnit = v; }
}
