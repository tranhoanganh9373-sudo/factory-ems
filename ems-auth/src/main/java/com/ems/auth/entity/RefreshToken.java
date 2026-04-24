package com.ems.auth.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id @Column(length = 64) private String jti;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "issued_at", nullable = false) private OffsetDateTime issuedAt;
    @Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt;
    @Column(name = "revoked_at") private OffsetDateTime revokedAt;

    public String getJti() { return jti; }
    public Long getUserId() { return userId; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setJti(String v) { this.jti = v; }
    public void setUserId(Long v) { this.userId = v; }
    public void setIssuedAt(OffsetDateTime v) { this.issuedAt = v; }
    public void setExpiresAt(OffsetDateTime v) { this.expiresAt = v; }
    public void setRevokedAt(OffsetDateTime v) { this.revokedAt = v; }
}
