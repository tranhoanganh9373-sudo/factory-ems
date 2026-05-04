package com.ems.meter.entity;

import com.ems.core.constant.ValueKind;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "meters")
public class Meter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "energy_type_id", nullable = false)
    private Long energyTypeId;

    @Column(name = "org_node_id", nullable = false)
    private Long orgNodeId;

    @Column(name = "influx_measurement", nullable = false, length = 64)
    private String influxMeasurement;

    @Column(name = "influx_tag_key", nullable = false, length = 64)
    private String influxTagKey;

    @Column(name = "influx_tag_value", nullable = false, length = 128)
    private String influxTagValue;

    @Column(nullable = false)
    private Boolean enabled = true;

    /**
     * 关联的 collector channel；nullable —— meter 不一定要绑 channel。
     * 当设置时，{@code InfluxSampleWriter} 会按 (channelId, channelPointKey) 反查并写入 InfluxDB。
     */
    @Column(name = "channel_id")
    private Long channelId;

    /**
     * 关联到 channel 的哪个 point.key；与 channelId 同进同退（DB CHECK 约束保证一致）。
     * V2.3.2 之前用 code 承担此职责，现在解耦：code 是纯业务标识，channelPointKey 是采集器侧索引键。
     */
    @Column(name = "channel_point_key", length = 64)
    private String channelPointKey;

    /**
     * 样本语义。决定时序查询如何聚合 (sum / last-first / integral)。
     * 默认 INTERVAL_DELTA — 与 V2.4.0 之前的隐式行为一致。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "value_kind", nullable = false, length = 32)
    private ValueKind valueKind = ValueKind.INTERVAL_DELTA;

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
    public Long getEnergyTypeId() { return energyTypeId; }
    public Long getOrgNodeId() { return orgNodeId; }
    public String getInfluxMeasurement() { return influxMeasurement; }
    public String getInfluxTagKey() { return influxTagKey; }
    public String getInfluxTagValue() { return influxTagValue; }
    public Boolean getEnabled() { return enabled; }
    public Long getChannelId() { return channelId; }
    public String getChannelPointKey() { return channelPointKey; }
    public ValueKind getValueKind() { return valueKind; }
    public Long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long v) { this.id = v; }
    public void setCode(String v) { this.code = v; }
    public void setName(String v) { this.name = v; }
    public void setEnergyTypeId(Long v) { this.energyTypeId = v; }
    public void setOrgNodeId(Long v) { this.orgNodeId = v; }
    public void setInfluxMeasurement(String v) { this.influxMeasurement = v; }
    public void setInfluxTagKey(String v) { this.influxTagKey = v; }
    public void setInfluxTagValue(String v) { this.influxTagValue = v; }
    public void setEnabled(Boolean v) { this.enabled = v; }
    public void setChannelId(Long v) { this.channelId = v; }
    public void setChannelPointKey(String v) { this.channelPointKey = v; }
    public void setValueKind(ValueKind v) { this.valueKind = v; }
}
