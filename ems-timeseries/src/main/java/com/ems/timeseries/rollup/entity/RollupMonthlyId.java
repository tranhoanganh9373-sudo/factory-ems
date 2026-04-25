package com.ems.timeseries.rollup.entity;

import java.io.Serializable;
import java.util.Objects;

public class RollupMonthlyId implements Serializable {
    private Long meterId;
    private String yearMonth;

    public RollupMonthlyId() {}
    public RollupMonthlyId(Long meterId, String yearMonth) { this.meterId = meterId; this.yearMonth = yearMonth; }

    public Long getMeterId() { return meterId; }
    public String getYearMonth() { return yearMonth; }
    public void setMeterId(Long v) { this.meterId = v; }
    public void setYearMonth(String v) { this.yearMonth = v; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RollupMonthlyId other)) return false;
        return Objects.equals(meterId, other.meterId) && Objects.equals(yearMonth, other.yearMonth);
    }
    @Override public int hashCode() { return Objects.hash(meterId, yearMonth); }
}
