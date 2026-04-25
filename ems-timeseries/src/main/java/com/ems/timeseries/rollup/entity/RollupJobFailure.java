package com.ems.timeseries.rollup.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "rollup_job_failures")
public class RollupJobFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String granularity;

    @Column(name = "bucket_ts", nullable = false)
    private OffsetDateTime bucketTs;

    @Column(name = "meter_id")
    private Long meterId;

    @Column(nullable = false)
    private Integer attempt = 1;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "next_retry_at", nullable = false)
    private OffsetDateTime nextRetryAt;

    @Column(nullable = false)
    private Boolean abandoned = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public String getGranularity() { return granularity; }
    public OffsetDateTime getBucketTs() { return bucketTs; }
    public Long getMeterId() { return meterId; }
    public Integer getAttempt() { return attempt; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getNextRetryAt() { return nextRetryAt; }
    public Boolean getAbandoned() { return abandoned; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long v) { this.id = v; }
    public void setGranularity(String v) { this.granularity = v; }
    public void setBucketTs(OffsetDateTime v) { this.bucketTs = v; }
    public void setMeterId(Long v) { this.meterId = v; }
    public void setAttempt(Integer v) { this.attempt = v; }
    public void setLastError(String v) { this.lastError = v; }
    public void setNextRetryAt(OffsetDateTime v) { this.nextRetryAt = v; }
    public void setAbandoned(Boolean v) { this.abandoned = v; }
}
