package com.ems.report.preset.impl;

import com.ems.billing.dto.BillDTO;
import com.ems.billing.dto.BillPeriodDTO;
import com.ems.billing.service.BillingService;
import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.orgtree.dto.OrgNodeDTO;
import com.ems.orgtree.service.OrgNodeService;
import com.ems.production.dto.ShiftDTO;
import com.ems.production.service.ShiftService;
import com.ems.report.matrix.ReportMatrix;
import com.ems.report.matrix.ReportMatrixRequest;
import com.ems.report.matrix.ReportQueryService;
import com.ems.report.preset.ReportPresetService;
import com.ems.timeseries.model.Granularity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final BillingService billing;
    private final OrgNodeService orgNodes;

    public ReportPresetServiceImpl(ReportQueryService query, ShiftService shifts,
                                   BillingService billing, OrgNodeService orgNodes) {
        this.query = query;
        this.shifts = shifts;
        this.billing = billing;
        this.orgNodes = orgNodes;
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

    @Override
    public ReportMatrix costMonthly(YearMonth ym, Long orgNodeId) {
        require(ym != null, "ym 不能为空");
        List<ReportMatrix.Column> columns = List.of(
                new ReportMatrix.Column("SHARP",  "尖"),
                new ReportMatrix.Column("PEAK",   "峰"),
                new ReportMatrix.Column("FLAT",   "平"),
                new ReportMatrix.Column("VALLEY", "谷"),
                new ReportMatrix.Column("TOTAL",  "合计")
        );

        BillPeriodDTO period;
        try {
            period = billing.getPeriodByYearMonth(ym.toString());
        } catch (IllegalArgumentException e) {
            // 账期不存在 → 返回空 matrix，前端展示 "暂无账单"
            return new ReportMatrix(
                    "成本月报 " + ym,
                    ReportMatrix.RowDimension.COST_CENTER,
                    ReportMatrix.ColumnDimension.TARIFF_BAND,
                    "CNY", columns, List.of(),
                    List.of(0.0, 0.0, 0.0, 0.0, 0.0), 0.0
            );
        }

        // 仅取 ELEC 账单：4 段电价拆分只对电有意义
        List<BillDTO> bills = billing.listBills(period.id(), orgNodeId).stream()
                .filter(b -> b.energyType() == EnergyTypeCode.ELEC)
                .sorted(Comparator.comparing(BillDTO::orgNodeId))
                .toList();

        List<ReportMatrix.Row> rows = new ArrayList<>(bills.size());
        double cSharp = 0, cPeak = 0, cFlat = 0, cValley = 0, cTotal = 0;
        for (BillDTO b : bills) {
            double sharp  = nz(b.sharpAmount());
            double peak   = nz(b.peakAmount());
            double flat   = nz(b.flatAmount());
            double valley = nz(b.valleyAmount());
            double total  = nz(b.amount());

            String orgName;
            try {
                OrgNodeDTO n = orgNodes.getById(b.orgNodeId());
                orgName = n.name();
            } catch (Exception e) {
                orgName = "Node " + b.orgNodeId();
            }

            rows.add(new ReportMatrix.Row(
                    String.valueOf(b.orgNodeId()),
                    orgName,
                    List.of(sharp, peak, flat, valley, total),
                    total
            ));

            cSharp += sharp; cPeak += peak; cFlat += flat; cValley += valley; cTotal += total;
        }

        return new ReportMatrix(
                "成本月报 " + ym,
                ReportMatrix.RowDimension.COST_CENTER,
                ReportMatrix.ColumnDimension.TARIFF_BAND,
                "CNY",
                columns,
                rows,
                List.of(cSharp, cPeak, cFlat, cValley, cTotal),
                cTotal
        );
    }

    private static double nz(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new BusinessException(ErrorCode.PARAM_INVALID, msg);
    }
}
