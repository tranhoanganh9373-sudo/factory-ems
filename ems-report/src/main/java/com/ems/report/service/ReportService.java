package com.ems.report.service;

import com.ems.report.dto.ReportRequest;
import com.ems.report.dto.ReportRow;

import java.util.stream.Stream;

/**
 * Ad-hoc 报表查询入口。
 * 实现层负责：权限过滤（DashboardSupport.resolveMeters）+ 时序混合查询 + 行扁平化。
 */
public interface ReportService {

    /**
     * 查询并返回行流。Caller 必须 try-with-resources / .close()，否则可能持有底层 ResultSet。
     * MVP 版本一次性加载到 List 后转 stream；后续可改为真正的游标流。
     */
    Stream<ReportRow> queryStream(ReportRequest req);
}
