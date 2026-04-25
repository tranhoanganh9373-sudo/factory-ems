package com.ems.dashboard.support;

import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.dashboard.dto.RangeQuery;
import com.ems.dashboard.dto.RangeType;
import com.ems.timeseries.model.TimeRange;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** 把 RangeQuery 解析为 [start, end) 时间区间。所有看板共用。 */
public final class RangeResolver {

    /** 系统报表 / 看板默认按本地时区切日。MVP 简化为东八区，后续可改为 application property。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private RangeResolver() {}

    public static TimeRange resolve(RangeQuery q) {
        return resolve(q, Clock.system(ZONE));
    }

    public static TimeRange resolve(RangeQuery q, Clock clock) {
        if (q == null || q.range() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "range 不能为空");
        }
        var now = clock.instant();
        return switch (q.range()) {
            case TODAY -> {
                var startOfDay = LocalDate.now(clock).atStartOfDay(ZONE).toInstant();
                yield new TimeRange(startOfDay, now);
            }
            case YESTERDAY -> {
                var today = LocalDate.now(clock).atStartOfDay(ZONE).toInstant();
                var yest = LocalDate.now(clock).minusDays(1).atStartOfDay(ZONE).toInstant();
                yield new TimeRange(yest, today);
            }
            case THIS_MONTH -> {
                var firstOfMonth = LocalDate.now(clock).withDayOfMonth(1).atStartOfDay(ZONE).toInstant();
                yield new TimeRange(firstOfMonth, now);
            }
            case LAST_24H -> {
                yield new TimeRange(now.minusSeconds(24L * 3600), now);
            }
            case CUSTOM -> {
                if (q.from() == null || q.to() == null) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "CUSTOM 区间需提供 from/to");
                }
                if (!q.to().isAfter(q.from())) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "to 必须晚于 from");
                }
                yield new TimeRange(q.from(), q.to());
            }
        };
    }

    /** Same-length window directly preceding `range`. 用于 mom 比较。 */
    public static TimeRange shiftBack(TimeRange range, long seconds) {
        var newStart = range.start().minusSeconds(seconds);
        var newEnd = range.end().minusSeconds(seconds);
        return new TimeRange(newStart, newEnd);
    }
}
