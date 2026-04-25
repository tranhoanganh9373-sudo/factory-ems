package com.ems.timeseries.rollup.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ts_rollup_daily")
@IdClass(RollupDailyId.class)
public class RollupDaily {

    @Id
    @Column(name = "meter_id", nullable = false)
    private Long meterId;

    @Id
    @Column(name = "day_date", nullable = false)
    private LocalDate dayDate;

    @Column(name = "org_node_id", nullable = false)
    private Long orgNodeId;

    @Column(name = "sum_value", nullable = false, precision = 20, scale = 6)
    private BigDecimal sumValue;

    @Column(name = "avg_value", nullable = false, precision = 20, scale = 6)
    private BigDecimal avgValue;

    @Column(name = "max_value", nullable = false, precision = 20, scale = 6)
    private BigDecimal maxValue;

    @Column(name = "min_value", nullable = false, precision = 20, scale = 6)
    private BigDecimal minValue;

    @Column(nullable = false)
    private Integer count;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getMeterId() { return meterId; }
    public LocalDate getDayDate() { return dayDate; }
    public Long getOrgNodeId() { return orgNodeId; }
    public BigDecimal getSumValue() { return sumValue; }
    public BigDecimal getAvgValue() { return avgValue; }
    public BigDecimal getMaxValue() { return maxValue; }
    public BigDecimal getMinValue() { return minValue; }
    public Integer getCount() { return count; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setMeterId(Long v) { this.meterId = v; }
    public void setDayDate(LocalDate v) { this.dayDate = v; }
    public void setOrgNodeId(Long v) { this.orgNodeId = v; }
    public void setSumValue(BigDecimal v) { this.sumValue = v; }
    public void setAvgValue(BigDecimal v) { this.avgValue = v; }
    public void setMaxValue(BigDecimal v) { this.maxValue = v; }
    public void setMinValue(BigDecimal v) { this.minValue = v; }
    public void setCount(Integer v) { this.count = v; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
