package com.ems.dashboard.service;

import com.ems.dashboard.dto.PvCurveDTO;
import com.ems.timeseries.model.TimeRange;

import java.math.BigDecimal;

public interface SolarSelfConsumptionService {

    /**
     * 区间汇总：按小时桶累加 max(0, gen − exp) 后求和（避免日内倒挂被平均掉）。
     * 关键不变量：先按桶算 self 再合，禁止先合再算。
     */
    SelfConsumptionSummary summarize(Long orgNodeId, TimeRange range);

    /** 每小时桶 (PV 发电, 厂区负载) 双线，用于前端 PvVsLoadPanel 双 Y 轴。 */
    PvCurveDTO curve(Long orgNodeId, TimeRange range);

    record SelfConsumptionSummary(
        BigDecimal generation,        // PV 总发电 kWh
        BigDecimal export,            // 上网 kWh
        BigDecimal selfConsumption,   // Σ_hour max(0, gen_h − exp_h)
        BigDecimal selfRatio          // self / generation；generation=0 → null
    ) {}
}
