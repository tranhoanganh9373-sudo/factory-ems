package com.ems.audit.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id")   private Long actorUserId;
    @Column(name = "actor_username")  private String actorUsername;
    @Column(nullable = false)         private String action;
    @Column(name = "resource_type")   private String resourceType;
    @Column(name = "resource_id")     private String resourceId;
    @Column(columnDefinition = "text") private String summary;

    @Column(columnDefinition = "jsonb")
    private String detail;

    private String ip;

    @Column(name = "user_agent", columnDefinition = "text")
    private String userAgent;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    public Long getId() { return id; }
    public Long getActorUserId() { return actorUserId; }
    public String getActorUsername() { return actorUsername; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getSummary() { return summary; }
    public String getDetail() { return detail; }
    public String getIp() { return ip; }
    public String getUserAgent() { return userAgent; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }

    public void setActorUserId(Long v) { this.actorUserId = v; }
    public void setActorUsername(String v) { this.actorUsername = v; }
    public void setAction(String v) { this.action = v; }
    public void setResourceType(String v) { this.resourceType = v; }
    public void setResourceId(String v) { this.resourceId = v; }
    public void setSummary(String v) { this.summary = v; }
    public void setDetail(String v) { this.detail = v; }
    public void setIp(String v) { this.ip = v; }
    public void setUserAgent(String v) { this.userAgent = v; }
    public void setOccurredAt(OffsetDateTime v) { this.occurredAt = v; }
}
