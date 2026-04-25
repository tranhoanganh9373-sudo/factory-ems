package com.ems.timeseries.rollup;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.RollupReaderPort;
import com.ems.timeseries.rollup.entity.RollupDaily;
import com.ems.timeseries.rollup.entity.RollupHourly;
import com.ems.timeseries.rollup.entity.RollupMonthly;
import com.ems.timeseries.rollup.repository.RollupDailyRepository;
import com.ems.timeseries.rollup.repository.RollupHourlyRepository;
import com.ems.timeseries.rollup.repository.RollupMonthlyRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 真正的 rollup 读取实现。覆盖 NoopRollupReader（同时存在时由 ConditionalOnMissingBean 保证 noop 不注册）。
 */
@Component
public class RollupReaderImpl implements RollupReaderPort {

    private final RollupHourlyRepository hourlyRepo;
    private final RollupDailyRepository dailyRepo;
    private final RollupMonthlyRepository monthlyRepo;

    public RollupReaderImpl(RollupHourlyRepository hourlyRepo,
                            RollupDailyRepository dailyRepo,
                            RollupMonthlyRepository monthlyRepo) {
        this.hourlyRepo = hourlyRepo;
        this.dailyRepo = dailyRepo;
        this.monthlyRepo = monthlyRepo;
    }

    @Override
    public Map<Long, List<TimePoint>> readBuckets(Collection<Long> meterIds, TimeRange range, Granularity granularity) {
        if (meterIds == null || meterIds.isEmpty()) return Collections.emptyMap();
        Map<Long, List<TimePoint>> out = new HashMap<>();
        switch (granularity) {
            case HOUR -> {
                List<RollupHourly> rows = hourlyRepo.findInRange(meterIds,
                    toOdt(range.start()), toOdt(range.end()));
                for (RollupHourly r : rows) {
                    out.computeIfAbsent(r.getMeterId(), k -> new ArrayList<>())
                       .add(new TimePoint(r.getHourTs().toInstant(), r.getSumValue().doubleValue()));
                }
            }
            case DAY -> {
                List<RollupDaily> rows = dailyRepo.findInRange(meterIds,
                    range.start().atOffset(ZoneOffset.UTC).toLocalDate(),
                    range.end().atOffset(ZoneOffset.UTC).toLocalDate());
                for (RollupDaily r : rows) {
                    out.computeIfAbsent(r.getMeterId(), k -> new ArrayList<>())
                       .add(new TimePoint(r.getDayDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
                                          r.getSumValue().doubleValue()));
                }
            }
            case MONTH -> {
                String startYm = YearMonth.from(range.start().atOffset(ZoneOffset.UTC)).toString();
                String endYm   = YearMonth.from(range.end().atOffset(ZoneOffset.UTC)).toString();
                List<RollupMonthly> rows = monthlyRepo.findInRange(meterIds, startYm, endYm);
                for (RollupMonthly r : rows) {
                    YearMonth ym = YearMonth.parse(r.getYearMonth());
                    out.computeIfAbsent(r.getMeterId(), k -> new ArrayList<>())
                       .add(new TimePoint(ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                                          r.getSumValue().doubleValue()));
                }
            }
            case MINUTE -> { /* minute 级不走 rollup */ }
        }
        return out;
    }

    @Override
    public Map<Long, Double> sumByMeter(Collection<Long> meterIds, TimeRange range, Granularity granularity) {
        Map<Long, List<TimePoint>> buckets = readBuckets(meterIds, range, granularity);
        Map<Long, Double> out = new HashMap<>();
        buckets.forEach((id, pts) ->
            out.put(id, pts.stream().mapToDouble(TimePoint::value).sum()));
        return out;
    }

    @Override
    public Instant rollupBoundary(Granularity granularity) {
        return switch (granularity) {
            case HOUR -> hourlyRepo.findMaxHour()
                .map(odt -> odt.toInstant().plusSeconds(3600))
                .orElse(Instant.MIN);
            case DAY -> dailyRepo.findMaxDay()
                .map(d -> d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                .orElse(Instant.MIN);
            case MONTH -> monthlyRepo.findMaxYearMonth()
                .map(ym -> YearMonth.parse(ym).plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                .orElse(Instant.MIN);
            case MINUTE -> Instant.MIN;
        };
    }

    private static OffsetDateTime toOdt(Instant i) { return i.atOffset(ZoneOffset.UTC); }
}
