package com.ems.report.carbon;

import com.ems.timeseries.model.TimeRange;

public interface CarbonReportService {

    /**
     * 区间内某 org 的碳排报告：
     *   reduction_kg = self_consumption_kwh × (carbon_factor[GRID] − carbon_factor[SOLAR])
     * 反映 PV 自发自用替代电网用电产生的减排当量。
     */
    CarbonReportDTO compute(Long orgNodeId, TimeRange range);
}
