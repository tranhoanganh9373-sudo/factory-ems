package com.ems.alarm.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "topology_consistency_history")
public class TopologyConsistencyHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_meter_id", nullable = false)
    private Long parentMeterId;

    @Column(name = "energy_type", nullable = false, length = 16)
    private String energyType;

    @Column(name = "parent_reading", nullable = false, precision = 20, scale = 4)
    private BigDecimal parentReading;

    @Column(name = "children_sum", nullable = false, precision = 20, scale = 4)
    private BigDecimal childrenSum;

    @Column(name = "children_count", nullable = false)
    private Integer childrenCount;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal residual;

    @Column(name = "residual_ratio", precision = 8, scale = 5)
    private BigDecimal residualRatio;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(name = "sampled_at", nullable = false)
    private OffsetDateTime sampledAt;

    protected TopologyConsistencyHistory() {}

    public TopologyConsistencyHistory(Long parentMeterId, String energyType,
                                      BigDecimal parentReading, BigDecimal childrenSum,
                                      Integer childrenCount, BigDecimal residual,
                                      BigDecimal residualRatio, String severity,
                                      OffsetDateTime sampledAt) {
        this.parentMeterId = parentMeterId;
        this.energyType = energyType;
        this.parentReading = parentReading;
        this.childrenSum = childrenSum;
        this.childrenCount = childrenCount;
        this.residual = residual;
        this.residualRatio = residualRatio;
        this.severity = severity;
        this.sampledAt = sampledAt;
    }

    public Long getId() { return id; }
    public Long getParentMeterId() { return parentMeterId; }
    public String getEnergyType() { return energyType; }
    public BigDecimal getParentReading() { return parentReading; }
    public BigDecimal getChildrenSum() { return childrenSum; }
    public Integer getChildrenCount() { return childrenCount; }
    public BigDecimal getResidual() { return residual; }
    public BigDecimal getResidualRatio() { return residualRatio; }
    public String getSeverity() { return severity; }
    public OffsetDateTime getSampledAt() { return sampledAt; }
}
