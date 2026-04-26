package com.ems.billing.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * 账期 (billing period). State machine:
 *   OPEN -> CLOSED  via close()
 *   CLOSED -> CLOSED via close()  (重写：先删旧 bill+bill_line 再写新)
 *   CLOSED -> LOCKED via lock()
 *   LOCKED -> CLOSED via unlock() (ADMIN only, audited)
 * Any other transition throws IllegalStateException.
 */
@Entity
@Table(name = "bill_period")
public class BillPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "year_month", nullable = false, length = 7, unique = true)
    private String yearMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BillPeriodStatus status = BillPeriodStatus.OPEN;

    @Column(name = "period_start", nullable = false)
    private OffsetDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private OffsetDateTime periodEnd;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "locked_at")
    private OffsetDateTime lockedAt;

    @Column(name = "locked_by")
    private Long lockedBy;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** OPEN/CLOSED -> CLOSED. CLOSED 重写允许（service 负责删旧 bill+line 后再写）。 */
    public void close(Long actorUserId) {
        if (status == BillPeriodStatus.LOCKED) {
            throw new IllegalStateException("bill_period " + yearMonth + " is LOCKED; cannot close");
        }
        this.status = BillPeriodStatus.CLOSED;
        this.closedAt = OffsetDateTime.now();
        this.closedBy = actorUserId;
    }

    /** CLOSED -> LOCKED. 必须先 close。 */
    public void lock(Long actorUserId) {
        if (status != BillPeriodStatus.CLOSED) {
            throw new IllegalStateException(
                "bill_period " + yearMonth + " must be CLOSED to lock; current=" + status);
        }
        this.status = BillPeriodStatus.LOCKED;
        this.lockedAt = OffsetDateTime.now();
        this.lockedBy = actorUserId;
    }

    /** LOCKED -> CLOSED. ADMIN only + audit。 */
    public void unlock() {
        if (status != BillPeriodStatus.LOCKED) {
            throw new IllegalStateException(
                "bill_period " + yearMonth + " must be LOCKED to unlock; current=" + status);
        }
        this.status = BillPeriodStatus.CLOSED;
        this.lockedAt = null;
        this.lockedBy = null;
    }

    /** Service-level guard: any write to bill/bill_line within a LOCKED period must be rejected. */
    public void assertWritable() {
        if (status == BillPeriodStatus.LOCKED) {
            throw new IllegalStateException("bill_period " + yearMonth + " is LOCKED; writes rejected");
        }
    }

    public boolean isLocked() { return status == BillPeriodStatus.LOCKED; }
    public boolean isClosed() { return status == BillPeriodStatus.CLOSED; }
    public boolean isOpen() { return status == BillPeriodStatus.OPEN; }

    public Long getId() { return id; }
    public String getYearMonth() { return yearMonth; }
    public BillPeriodStatus getStatus() { return status; }
    public OffsetDateTime getPeriodStart() { return periodStart; }
    public OffsetDateTime getPeriodEnd() { return periodEnd; }
    public OffsetDateTime getClosedAt() { return closedAt; }
    public Long getClosedBy() { return closedBy; }
    public OffsetDateTime getLockedAt() { return lockedAt; }
    public Long getLockedBy() { return lockedBy; }
    public Long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long v) { this.id = v; }
    public void setYearMonth(String v) { this.yearMonth = v; }
    public void setStatus(BillPeriodStatus v) { this.status = v; }
    public void setPeriodStart(OffsetDateTime v) { this.periodStart = v; }
    public void setPeriodEnd(OffsetDateTime v) { this.periodEnd = v; }
}
