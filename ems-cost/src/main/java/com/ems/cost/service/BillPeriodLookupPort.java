package com.ems.cost.service;

import java.time.OffsetDateTime;

/**
 * 让 ems-cost 通过 bill_period_id 拿到该账期的精确 (start, end) 边界。
 * 实现位于 ems-billing（持有 BillPeriodRepository）。
 *
 * 引入这个端口是为了消除"前端拼 ISO UTC 时间戳给 cost-allocation/runs"
 * 与"BillPeriod 在 Asia/Shanghai 存边界"之间的时区错配（issue #24）：
 * 调用方传 billPeriodId，后端从 bill_period 直接读边界，单一事实源。
 */
public interface BillPeriodLookupPort {

    /** Resolved BillPeriod boundaries — both fields non-null. */
    record BillPeriodBoundaries(OffsetDateTime periodStart, OffsetDateTime periodEnd) {}

    /**
     * Resolve a BillPeriod's (periodStart, periodEnd) by id.
     *
     * @throws BillPeriodNotFoundException 当 id 不存在时
     */
    BillPeriodBoundaries findBoundariesById(Long billPeriodId);

    /** Thrown when a bill_period row is not found by id. */
    class BillPeriodNotFoundException extends RuntimeException {
        public BillPeriodNotFoundException(Long id) {
            super("bill period not found: id=" + id);
        }
    }
}
