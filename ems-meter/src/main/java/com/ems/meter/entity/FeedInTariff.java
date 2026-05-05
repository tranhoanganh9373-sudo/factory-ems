package com.ems.meter.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "feed_in_tariff")
public class FeedInTariff {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(name = "energy_source", nullable = false, length = 16)
    private EnergySource energySource;

    @Column(name = "period_type", nullable = false, length = 8)
    private String periodType;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal price;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected FeedInTariff() {}

    public FeedInTariff(String region, EnergySource energySource, String periodType,
                        LocalDate effectiveFrom, BigDecimal price) {
        this.region = region;
        this.energySource = energySource;
        this.periodType = periodType;
        this.effectiveFrom = effectiveFrom;
        this.price = price;
    }

    public Long getId() { return id; }
    public String getRegion() { return region; }
    public EnergySource getEnergySource() { return energySource; }
    public String getPeriodType() { return periodType; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public BigDecimal getPrice() { return price; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
