package com.ems.timeseries.rollup.entity;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

public class RollupHourlyId implements Serializable {
    private Long meterId;
    private OffsetDateTime hourTs;

    public RollupHourlyId() {}
    public RollupHourlyId(Long meterId, OffsetDateTime hourTs) { this.meterId = meterId; this.hourTs = hourTs; }

    public Long getMeterId() { return meterId; }
    public OffsetDateTime getHourTs() { return hourTs; }
    public void setMeterId(Long v) { this.meterId = v; }
    public void setHourTs(OffsetDateTime v) { this.hourTs = v; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RollupHourlyId other)) return false;
        return Objects.equals(meterId, other.meterId) && Objects.equals(hourTs, other.hourTs);
    }
    @Override public int hashCode() { return Objects.hash(meterId, hourTs); }
}
