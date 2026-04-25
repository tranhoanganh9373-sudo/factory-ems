package com.ems.timeseries.rollup.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

public class RollupDailyId implements Serializable {
    private Long meterId;
    private LocalDate dayDate;

    public RollupDailyId() {}
    public RollupDailyId(Long meterId, LocalDate dayDate) { this.meterId = meterId; this.dayDate = dayDate; }

    public Long getMeterId() { return meterId; }
    public LocalDate getDayDate() { return dayDate; }
    public void setMeterId(Long v) { this.meterId = v; }
    public void setDayDate(LocalDate v) { this.dayDate = v; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RollupDailyId other)) return false;
        return Objects.equals(meterId, other.meterId) && Objects.equals(dayDate, other.dayDate);
    }
    @Override public int hashCode() { return Objects.hash(meterId, dayDate); }
}
