package com.ems.report.preset;

import com.ems.report.matrix.ReportMatrix;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.List;

/**
 * 报表预设：
 *  - daily / monthly / yearly：固化时段 + 固化粒度，复用 ReportMatrix 内核
 *  - shift：按班次时段切片（含跨零点），列轴为能源品类
 */
public interface ReportPresetService {

    /** 日报：行 = 节点，列 = 小时（00:00 ~ 23:00），粒度 HOUR。 */
    ReportMatrix daily(LocalDate date, Long orgNodeId, List<String> energyTypes);

    /** 月报：行 = 节点，列 = 日（YYYY-MM-DD），粒度 DAY。 */
    ReportMatrix monthly(YearMonth ym, Long orgNodeId, List<String> energyTypes);

    /** 年报：行 = 节点，列 = 月（YYYY-MM），粒度 MONTH。 */
    ReportMatrix yearly(Year year, Long orgNodeId, List<String> energyTypes);

    /** 班次报表：date 当日的指定 shift 时段（跨零点 → 跨日），行 = 节点，列 = 能源品类。 */
    ReportMatrix shift(LocalDate date, Long shiftId, Long orgNodeId, List<String> energyTypes);

    /**
     * 子项目 2 · 成本月报：行 = 组织节点（成本中心），列 = 尖/峰/平/谷/合计 5 段（CNY）。
     * 数据来源是 bill 表的 ELEC 账单（4 段电价拆分仅对电有意义）。
     * orgNodeId 为空 = 当前账期下全部账单。账期不存在时返回空 matrix（不抛异常）。
     */
    ReportMatrix costMonthly(YearMonth ym, Long orgNodeId);
}
