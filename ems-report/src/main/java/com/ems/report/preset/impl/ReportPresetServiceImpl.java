package com.ems.report.preset.impl;

import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.production.dto.ShiftDTO;
import com.ems.production.service.ShiftService;
import com.ems.report.matrix.ReportMatrix;
import com.ems.report.matrix.ReportMatrixRequest;
import com.ems.report.matrix.ReportQueryService;
import com.ems.report.preset.ReportPresetService;
import com.ems.timeseries.model.Granularity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * 预设报表统一委托 ReportQueryService.query(...)。预设的职责仅是：
 *  1) 把"日/月/年/班次 + orgNodeId"组装成具体 TimeRange + 粒度
 *  2) 选择行/列维度
 *  3) 组装合适的 title
 *
 * 时区：所有日期 / 月 / 年都按 Asia/Shanghai 解析；查询用 Instant 区间。
 */
@Service
public class ReportPresetServiceImpl implements ReportPresetService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ReportQueryService query;
    private final ShiftService shifts;

    public ReportPresetServiceImpl(ReportQueryService query, ShiftService shifts) {
        this.query = query;
        this.shifts = shifts;
    }

    @Override
    public ReportMatrix daily(LocalDate date, Long orgNodeId, List<String> energyTypes) {
        require(date != null, "date 不能为空");
        Instant from = date.atStartOfDay(ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        return query.query(new ReportMatrixRequest(
                from, to, Granularity.HOUR, orgNodeId, energyTypes, null,
                ReportMatrix.RowDimension.ORG_NODE,
                ReportMatrix.ColumnDimension.TIME_BUCKET,
                "日报 " + date
        ));
    }

    @Override
    public ReportMatrix monthly(YearMonth ym, Long orgNodeId, List<String> energyTypes) {
        require(ym != null, "ym 不能为空");
        Instant from = ym.atDay(1).atStartOfDay(ZONE).toInstant();
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
        return query.query(new ReportMatrixRequest(
                from, to, Granularity.DAY, orgNodeId, energyTypes, null,
                ReportMatrix.RowDimension.ORG_NODE,
                ReportMatrix.ColumnDimension.TIME_BUCKET,
                "月报 " + ym
        ));
    }

    @Override
    public ReportMatrix yearly(Year year, Long orgNodeId, List<String> energyTypes) {
        require(year != null, "year 不能为空");
        Instant from = year.atDay(1).atStartOfDay(ZONE).toInstant();
        Instant to = year.plusYears(1).atDay(1).atStartOfDay(ZONE).toInstant();
        return query.query(new ReportMatrixRequest(
                from, to, Granularity.MONTH, orgNodeId, energyTypes, null,
                ReportMatrix.RowDimension.ORG_NODE,
                ReportMatrix.ColumnDimension.TIME_BUCKET,
                "年报 " + year
        ));
    }

    @Override
    public ReportMatrix shift(LocalDate date, Long shiftId, Long orgNodeId, List<String> energyTypes) {
        require(date != null, "date 不能为空");
        require(shiftId != null, "shiftId 不能为空");
        ShiftDTO s = shifts.getById(shiftId);
        Instant[] range = resolveShiftRange(date, s.timeStart(), s.timeEnd());
        return query.query(new ReportMatrixRequest(
                range[0], range[1], Granularity.HOUR, orgNodeId, energyTypes, null,
                ReportMatrix.RowDimension.ORG_NODE,
                ReportMatrix.ColumnDimension.ENERGY_TYPE,
                "班次报表 " + date + " " + s.code()
        ));
    }

    /** 跨零点：start <= end 同日；start > end 跨到次日。 */
    static Instant[] resolveShiftRange(LocalDate date, LocalTime start, LocalTime end) {
        LocalDateTime startDt = LocalDateTime.of(date, start);
        LocalDateTime endDt = LocalDateTime.of(date, end);
        if (!start.isBefore(end)) {
            // start >= end → 跨零点（包含 start == end 视为 24h，但实际业务无此情况；防御性按跨日）
            endDt = endDt.plusDays(1);
        }
        return new Instant[] {
                startDt.atZone(ZONE).toInstant(),
                endDt.atZone(ZONE).toInstant()
        };
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new BusinessException(ErrorCode.PARAM_INVALID, msg);
    }
}
