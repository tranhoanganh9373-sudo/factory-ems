package com.ems.floorplan.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "floorplans")
public class Floorplan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "org_node_id", nullable = false)
    private Long orgNodeId;

    @Column(name = "file_path", nullable = false, length = 512, unique = true)
    private String filePath;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "width_px", nullable = false)
    private int widthPx;

    @Column(name = "height_px", nullable = false)
    private int heightPx;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Version
    private Long version;

    @Column(name = "created_by")
    private Long createdBy;

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
    public String getName() { return name; }
    public Long getOrgNodeId() { return orgNodeId; }
    public String getFilePath() { return filePath; }
    public String getContentType() { return contentType; }
    public int getWidthPx() { return widthPx; }
    public int getHeightPx() { return heightPx; }
    public long getFileSizeBytes() { return fileSizeBytes; }
    public Boolean getEnabled() { return enabled; }
    public Long getVersion() { return version; }
    public Long getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String v) { this.name = v; }
    public void setOrgNodeId(Long v) { this.orgNodeId = v; }
    public void setFilePath(String v) { this.filePath = v; }
    public void setContentType(String v) { this.contentType = v; }
    public void setWidthPx(int v) { this.widthPx = v; }
    public void setHeightPx(int v) { this.heightPx = v; }
    public void setFileSizeBytes(long v) { this.fileSizeBytes = v; }
    public void setEnabled(Boolean v) { this.enabled = v; }
    public void setCreatedBy(Long v) { this.createdBy = v; }
}
