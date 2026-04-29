package com.ems.alarm.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "alarms")
public class Alarm {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_type", nullable = false, length = 32)
    private String deviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "alarm_type", nullable = false, length = 32)
    private AlarmType alarmType;

    @Column(nullable = false, length = 16)
    private String severity = "WARNING";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AlarmStatus status;

    @Column(name = "triggered_at", nullable = false)
    private OffsetDateTime triggeredAt;

    @Column(name = "acked_at")
    private OffsetDateTime ackedAt;

    @Column(name = "acked_by")
    private Long ackedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolved_reason", length = 32)
    private ResolvedReason resolvedReason;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    // === getters & setters ===
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public AlarmType getAlarmType() { return alarmType; }
    public void setAlarmType(AlarmType alarmType) { this.alarmType = alarmType; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public AlarmStatus getStatus() { return status; }
    public void setStatus(AlarmStatus status) { this.status = status; }
    public OffsetDateTime getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(OffsetDateTime t) { this.triggeredAt = t; }
    public OffsetDateTime getAckedAt() { return ackedAt; }
    public void setAckedAt(OffsetDateTime t) { this.ackedAt = t; }
    public Long getAckedBy() { return ackedBy; }
    public void setAckedBy(Long ackedBy) { this.ackedBy = ackedBy; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime t) { this.resolvedAt = t; }
    public ResolvedReason getResolvedReason() { return resolvedReason; }
    public void setResolvedReason(ResolvedReason r) { this.resolvedReason = r; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime t) { this.lastSeenAt = t; }
    public Map<String, Object> getDetail() { return detail; }
    public void setDetail(Map<String, Object> detail) { this.detail = detail; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
