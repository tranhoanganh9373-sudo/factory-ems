package com.ems.alarm.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "alarm_rules_override")
public class AlarmRuleOverride {
    @Id
    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "silent_timeout_seconds")
    private Integer silentTimeoutSeconds;

    @Column(name = "consecutive_fail_count")
    private Integer consecutiveFailCount;

    @Column(name = "maintenance_mode", nullable = false)
    private boolean maintenanceMode;

    @Column(name = "maintenance_note", length = 255)
    private String maintenanceNote;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }
    public Integer getSilentTimeoutSeconds() { return silentTimeoutSeconds; }
    public void setSilentTimeoutSeconds(Integer v) { this.silentTimeoutSeconds = v; }
    public Integer getConsecutiveFailCount() { return consecutiveFailCount; }
    public void setConsecutiveFailCount(Integer v) { this.consecutiveFailCount = v; }
    public boolean isMaintenanceMode() { return maintenanceMode; }
    public void setMaintenanceMode(boolean v) { this.maintenanceMode = v; }
    public String getMaintenanceNote() { return maintenanceNote; }
    public void setMaintenanceNote(String v) { this.maintenanceNote = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long v) { this.updatedBy = v; }
}
