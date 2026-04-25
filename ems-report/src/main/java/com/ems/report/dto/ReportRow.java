package com.ems.report.dto;

import java.time.Instant;

/** 单行扁平结果：(时间桶, 测点, 组织, 能源品类, 数值)。CSV 一行就是一个 ReportRow。 */
public record ReportRow(
    Instant ts,
    Long meterId,
    String meterCode,
    String meterName,
    Long orgNodeId,
    String energyTypeCode,
    String unit,
    Double value
) {}
