package com.ems.timeseries.query;

import com.ems.core.constant.ValueKind;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimeRange;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 时序数据查询统一入口。其他模块只通过此接口读 InfluxDB / 预聚合表。 */
public interface TimeSeriesQueryService {

    /** 按测点分组返回每个时间桶的聚合值。 */
    List<MeterPoint> queryByMeter(Collection<MeterRef> meters, TimeRange range, Granularity granularity);

    /** 单测点区间总量（按 valueKind 选择 sum / last-first / integral），多测点一次返回。 */
    Map<Long, Double> sumByMeter(Collection<MeterRef> meters, TimeRange range);

    /** 按 energyType 聚合区间总量（meter→sum→groupBy energyType）。 */
    Map<String, Double> sumByEnergyType(Collection<MeterRef> meters, TimeRange range);

    /**
     * 调用方传入的 meter 引用：包含 InfluxDB tag、PG 主键映射、以及样本语义。
     * valueKind 决定 sumByMeter 的聚合算子：sum / last-first / integral。
     */
    record MeterRef(Long meterId, String influxTagValue, String energyTypeCode, ValueKind valueKind) {
        /** 兼容旧 3 元构造：默认 valueKind = INTERVAL_DELTA（V2.4.0 之前的隐式行为）。 */
        public MeterRef(Long meterId, String influxTagValue, String energyTypeCode) {
            this(meterId, influxTagValue, energyTypeCode, ValueKind.INTERVAL_DELTA);
        }
    }
}
