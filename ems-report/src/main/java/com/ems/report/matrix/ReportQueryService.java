package com.ems.report.matrix;

/**
 * 报表统一内核：返回二维 ReportMatrix（CSV/Excel/PDF 共享）。
 * 实现：权限过滤（DashboardSupport.resolveMeters）+ 时序混合查询 + 二维透视聚合。
 */
public interface ReportQueryService {
    ReportMatrix query(ReportMatrixRequest req);
}
