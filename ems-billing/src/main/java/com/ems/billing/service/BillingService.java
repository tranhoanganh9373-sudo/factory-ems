package com.ems.billing.service;

import com.ems.billing.dto.BillDTO;
import com.ems.billing.dto.BillPeriodDTO;

import java.time.YearMonth;
import java.util.List;

public interface BillingService {

    /**
     * 生成（或重新生成）该账期的所有账单。
     * 前置：BillPeriod 必须存在且 status != LOCKED；必须存在一个 SUCCESS 的 cost run 完全覆盖账期。
     * 副作用：CLOSED → CLOSED 时先删旧 bill+bill_line 再写新；最终 BillPeriod 进入 CLOSED。
     */
    BillPeriodDTO generateBills(Long periodId, Long actorUserId);

    /** 根据 yearMonth 创建一个 OPEN 的账期（如已存在则原样返回）。 */
    BillPeriodDTO ensurePeriod(YearMonth ym);

    /** 锁定账期（CLOSED → LOCKED）。调用方负责 audit。 */
    BillPeriodDTO lockPeriod(Long periodId, Long actorUserId);

    /** 解锁账期（LOCKED → CLOSED）。仅 ADMIN，调用方负责 audit。 */
    BillPeriodDTO unlockPeriod(Long periodId, Long actorUserId);

    BillPeriodDTO getPeriod(Long periodId);

    BillPeriodDTO getPeriodByYearMonth(String yearMonth);

    List<BillPeriodDTO> listPeriods();

    List<BillDTO> listBills(Long periodId, Long orgNodeId);

    BillDTO getBill(Long billId);

    /**
     * 看板面板 ⑩ 数据：找最近一次（可按账期过滤）SUCCESS cost run，按 org 聚合 quantity+amount。
     * period == null：取库里最新的 SUCCESS run（任意账期）。
     */
    com.ems.billing.dto.CostDistributionDTO costDistribution(java.time.YearMonth period);
}
