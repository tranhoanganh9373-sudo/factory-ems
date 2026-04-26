package com.ems.cost.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "cost_allocation_rule")
public class CostAllocationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "energy_type", nullable = false, length = 32)
    private EnergyTypeCode energyType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AllocationAlgorithm algorithm;

    @Column(name = "source_meter_id", nullable = false)
    private Long sourceMeterId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "target_org_ids", nullable = false, columnDefinition = "bigint[]")
    private Long[] targetOrgIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> weights = new HashMap<>();

    @Column(nullable = false)
    private Integer priority = 100;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Version
    private Long version;

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
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public EnergyTypeCode getEnergyType() { return energyType; }
    public AllocationAlgorithm getAlgorithm() { return algorithm; }
    public Long getSourceMeterId() { return sourceMeterId; }
    public Long[] getTargetOrgIds() { return targetOrgIds; }
    public Map<String, Object> getWeights() { return weights; }
    public Integer getPriority() { return priority; }
    public Boolean getEnabled() { return enabled; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public Long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setCode(String v) { this.code = v; }
    public void setName(String v) { this.name = v; }
    public void setDescription(String v) { this.description = v; }
    public void setEnergyType(EnergyTypeCode v) { this.energyType = v; }
    public void setAlgorithm(AllocationAlgorithm v) { this.algorithm = v; }
    public void setSourceMeterId(Long v) { this.sourceMeterId = v; }
    public void setTargetOrgIds(Long[] v) { this.targetOrgIds = v; }
    public void setWeights(Map<String, Object> v) { this.weights = v; }
    public void setPriority(Integer v) { this.priority = v; }
    public void setEnabled(Boolean v) { this.enabled = v; }
    public void setEffectiveFrom(LocalDate v) { this.effectiveFrom = v; }
    public void setEffectiveTo(LocalDate v) { this.effectiveTo = v; }
}
