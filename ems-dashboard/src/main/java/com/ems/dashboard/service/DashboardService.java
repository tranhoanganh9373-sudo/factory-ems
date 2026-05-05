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

    /**
     * ⑤ Top-N：按测点累计排名。
     * @param topN 排名条数（≤0 视为 10）
     * @param scope 拓扑筛选：LEAVES (默认，仅叶子表) / ROOTS (仅根表) / ALL (全部)；null/空白按 LEAVES 处理。
     */
    List<TopNItemDTO> topN(RangeQuery query, int topN, String scope);

    /** ⑥ 尖峰平谷分布：电耗按 tariff 时段类型聚合 + 占比。 */
    TariffDistributionDTO tariffDistribution(RangeQuery query);

    /** ⑦ 单位产量能耗：按日聚合电耗 / 产量 → 强度曲线。 */
    EnergyIntensityDTO energyIntensity(RangeQuery query);

    /** ⑧ 能流 Sankey：基于 meter_topology 父子关系，边权重 = source 累计读数。 */
    SankeyDTO sankey(RangeQuery query);

    /** ⑨ 平面图实时：底图 + 测点 + 当期累计 + 热力等级。 */
    FloorplanLiveDTO floorplanLive(Long floorplanId, RangeQuery query);

    /**
     * ⑩ 用电细分：visible 集合的根表 → 直接子表 + 其他/未分摊。
     * 残差 = root - Σ direct children (in visible set)；负残差表示数据/配置异常。
     */
    EnergyBreakdownDTO energyBreakdown(RangeQuery query);

    /**
     * ⑪ 拓扑一致性自检：遍历所有父子关系，对比父表读数与子表合计，输出残差 + severity。
     * 仅返回 visible 范围内、severity != OK 的行。给"系统健康"面板用。
     */
    java.util.List<TopologyConsistencyDTO> topologyConsistency(RangeQuery query);

    /**
     * ⑫ 能源来源构成（PV 功能门控）：按 EnergySource 分组汇总，返回各来源占比。
     * PV 未启用时返回空列表。
     */
    List<EnergySourceMixDTO> energySourceMix(RangeQuery query);

    /**
     * ⑬ PV 发电曲线（PV 功能门控）：按小时分桶，返回发电量与自耗量。
     * PV 未启用时返回空桶列表。
     */
    PvCurveDTO pvCurve(RangeQuery query);
}
