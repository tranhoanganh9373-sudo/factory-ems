package com.ems.dashboard.dto;

import com.ems.floorplan.dto.FloorplanDTO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 平面图 ⑨：底图 + 测点 + 当前累计读数 + 颜色级别（前端染色用）。
 * level：HIGH/MEDIUM/LOW/NONE，按相对最大值划档。
 */
public record FloorplanLiveDTO(
        FloorplanDTO floorplan,
        List<Point> points
) {
    public record Point(
            Long pointId,
            Long meterId,
            String meterCode,
            String meterName,
            String energyType,
            String unit,
            BigDecimal xRatio,
            BigDecimal yRatio,
            String label,
            double value,
            String level
    ) {}
}
