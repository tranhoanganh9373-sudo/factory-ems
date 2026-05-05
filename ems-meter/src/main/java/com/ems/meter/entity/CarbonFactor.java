package com.ems.meter.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "carbon_factor")
public class CarbonFactor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(name = "energy_source", nullable = false, length = 16)
    private EnergySource energySource;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "factor_kg_per_kwh", nullable = false, precision = 10, scale = 4)
    private BigDecimal factorKgPerKwh;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected CarbonFactor() {}

    public CarbonFactor(String region, EnergySource energySource, LocalDate effectiveFrom, BigDecimal factorKgPerKwh) {
        this.region = region;
        this.energySource = energySource;
        this.effectiveFrom = effectiveFrom;
        this.factorKgPerKwh = factorKgPerKwh;
    }

    public Long getId() { return id; }
    public String getRegion() { return region; }
    public EnergySource getEnergySource() { return energySource; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public BigDecimal getFactorKgPerKwh() { return factorKgPerKwh; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
