package com.ems.production.entity;

import jakarta.persistence.*;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "shifts")
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "time_start", nullable = false)
    private LocalTime timeStart;

    @Column(name = "time_end", nullable = false)
    private LocalTime timeEnd;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

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
    public LocalTime getTimeStart() { return timeStart; }
    public LocalTime getTimeEnd() { return timeEnd; }
    public Boolean getEnabled() { return enabled; }
    public Integer getSortOrder() { return sortOrder; }
    public Long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setCode(String v) { this.code = v; }
    public void setName(String v) { this.name = v; }
    public void setTimeStart(LocalTime v) { this.timeStart = v; }
    public void setTimeEnd(LocalTime v) { this.timeEnd = v; }
    public void setEnabled(Boolean v) { this.enabled = v; }
    public void setSortOrder(Integer v) { this.sortOrder = v; }
}
