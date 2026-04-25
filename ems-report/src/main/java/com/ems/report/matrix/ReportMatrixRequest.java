package com.ems.report.matrix;

import com.ems.timeseries.model.Granularity;

import java.time.Instant;
import java.util.List;

/**
 * ReportMatrix 查询参数。所有字段在 Service 层显式校验。
 *  - from / to: 时段（必填）
 *  - granularity: 时间桶粒度（HOUR / DAY / MONTH 可走 rollup）
 *  - orgNodeId: 限定子树（可空 → 全部可见节点）
 *  - energyTypes: 能源品类过滤
 *  - meterIds: 显式测点过滤
 *  - rowDimension / columnDimension: 透视维度
 *  - title: 报表显示标题（可空 → 默认按维度生成）
 */
public record ReportMatrixRequest(
        Instant from,
        Instant to,
        Granularity granularity,
        Long orgNodeId,
        List<String> energyTypes,
        List<Long> meterIds,
        ReportMatrix.RowDimension rowDimension,
        ReportMatrix.ColumnDimension columnDimension,
        String title
) {}
