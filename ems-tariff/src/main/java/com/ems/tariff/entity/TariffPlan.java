package com.ems.tariff.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tariff_plans")
public class TariffPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128, unique = true)
    private String name;

    @Column(name = "energy_type_id", nullable = false)
    private Long energyTypeId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(nullable = false)
    private Boolean enabled = true;

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
    public String getName() { return name; }
    public Long getEnergyTypeId() { return energyTypeId; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public Boolean getEnabled() { return enabled; }
    public Long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String v) { this.name = v; }
    public void setEnergyTypeId(Long v) { this.energyTypeId = v; }
    public void setEffectiveFrom(LocalDate v) { this.effectiveFrom = v; }
    public void setEffectiveTo(LocalDate v) { this.effectiveTo = v; }
    public void setEnabled(Boolean v) { this.enabled = v; }
}
