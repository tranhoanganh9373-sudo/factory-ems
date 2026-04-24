package com.ems.auth.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @Column(nullable = false, unique = true, length = 64) private String username;
    @Column(name = "password_hash", nullable = false, length = 128) private String passwordHash;
    @Column(name = "display_name") private String displayName;
    @Column(nullable = false) private Boolean enabled = true;
    @Column(name = "failed_attempts", nullable = false) private Integer failedAttempts = 0;
    @Column(name = "locked_until") private OffsetDateTime lockedUntil;
    @Column(name = "last_login_at") private OffsetDateTime lastLoginAt;

    @Version private Long version;

    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    @PrePersist void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = createdAt != null ? createdAt : now;
        updatedAt = now;
    }
    @PreUpdate void onUpdate() { updatedAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Boolean getEnabled() { return enabled; }
    public Integer getFailedAttempts() { return failedAttempts; }
    public OffsetDateTime getLockedUntil() { return lockedUntil; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long v) { this.id = v; }
    public void setUsername(String v) { this.username = v; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public void setDisplayName(String v) { this.displayName = v; }
    public void setEnabled(Boolean v) { this.enabled = v; }
    public void setFailedAttempts(Integer v) { this.failedAttempts = v; }
    public void setLockedUntil(OffsetDateTime v) { this.lockedUntil = v; }
    public void setLastLoginAt(OffsetDateTime v) { this.lastLoginAt = v; }
}
