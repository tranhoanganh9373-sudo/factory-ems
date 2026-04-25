package com.ems.dashboard.service;

import com.ems.dashboard.dto.*;

import java.util.List;

/**
 * 看板查询门面：5 个面板的 Service 入口。所有方法都已应用：
 *  - 节点权限过滤（PermissionResolver.visibleNodeIds）
 *  - orgNodeId 校验
 *  - 区间解析（RangeQuery → TimeRange）
 *  - rollup + Influx 混合查询（委托 TimeSeriesQueryService）
 */
public interface DashboardService {

    /** ① KPI 卡：每个能源品类的当期总量 + 环比 + 同比。 */
    List<KpiDTO> kpi(RangeQuery query);

    /** ② 24h 实时曲线：按 energyType 分组，按小时分桶。 */
    List<SeriesDTO> realtimeSeries(RangeQuery query);

    /** ③ 能源构成：当期各能源品类的占比饼图。 */
    List<CompositionDTO> energyComposition(RangeQuery query);

    /** ④ 测点详情：单测点曲线 + 累计。 */
    MeterDetailDTO meterDetail(Long meterId, RangeQuery query);

    /** ⑤ Top-N：按测点累计排名，topN 默认 10。 */
    List<TopNItemDTO> topN(RangeQuery query, int topN);

    /** ⑥ 尖峰平谷分布：电耗按 tariff 时段类型聚合 + 占比。 */
    TariffDistributionDTO tariffDistribution(RangeQuery query);

    /** ⑦ 单位产量能耗：按日聚合电耗 / 产量 → 强度曲线。 */
    EnergyIntensityDTO energyIntensity(RangeQuery query);
}
