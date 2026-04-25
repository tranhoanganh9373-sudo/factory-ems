package com.ems.timeseries.rollup;

import com.ems.timeseries.config.InfluxProperties;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.FluxQueryBuilder;
import com.ems.timeseries.rollup.entity.RollupJobFailure;
import com.ems.timeseries.rollup.repository.RollupDailyRepository;
import com.ems.timeseries.rollup.repository.RollupHourlyRepository;
import com.ems.timeseries.rollup.repository.RollupJobFailureRepository;
import com.ems.timeseries.rollup.repository.RollupMonthlyRepository;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 计算单个桶并 upsert PG。所有时间按 UTC 对齐。
 *
 * 失败处理：
 *  - 第一次失败 → 写 rollup_job_failures，attempt=1，next_retry = now+5min
 *  - 第二次 → attempt=2，next_retry = now+30min
 *  - 第三次 → attempt=3，next_retry = now+2h
 *  - 第四次（attempt 已是 3）→ abandoned=true
 */
@Service
public class RollupComputeService {

    private static final Logger log = LoggerFactory.getLogger(RollupComputeService.class);
    private static final long[] BACKOFF_SECONDS = { 5L * 60, 30L * 60, 2L * 60 * 60 };

    private final InfluxDBClient influx;
    private final InfluxProperties props;
    private final RollupHourlyRepository hourlyRepo;
    private final RollupDailyRepository dailyRepo;
    private final RollupMonthlyRepository monthlyRepo;
    private final RollupJobFailureRepository failureRepo;

    public RollupComputeService(InfluxDBClient influx, InfluxProperties props,
                                RollupHourlyRepository hourlyRepo,
                                RollupDailyRepository dailyRepo,
                                RollupMonthlyRepository monthlyRepo,
                                RollupJobFailureRepository failureRepo) {
        this.influx = influx;
        this.props = props;
        this.hourlyRepo = hourlyRepo;
        this.dailyRepo = dailyRepo;
        this.monthlyRepo = monthlyRepo;
        this.failureRepo = failureRepo;
    }

    public record MeterCtx(Long meterId, Long orgNodeId, String tagValue) {}

    /**
     * 主入口：计算并 upsert 单个 (meter, bucketStart, granularity)。
     * 抛异常时不再向上传播——内部记录失败行；调用方靠返回值决定是否继续。
     */
    public boolean computeBucket(MeterCtx meter, Granularity granularity, Instant bucketStart) {
        Instant aligned = BucketWindow.truncate(granularity, bucketStart);
        TimeRange window = BucketWindow.of(granularity, aligned);
        try {
            RollupAggregate agg = aggregateRaw(meter.tagValue(), window);
            upsert(meter, granularity, aligned, agg);
            clearFailure(granularity, aligned, meter.meterId());
            return true;
        } catch (RuntimeException ex) {
            log.warn("rollup compute failed: meter={} granularity={} bucket={} err={}",
                meter.meterId(), granularity, aligned, ex.toString());
            recordFailure(granularity, aligned, meter.meterId(), ex.toString());
            return false;
        }
    }

    @Transactional
    protected void upsert(MeterCtx meter, Granularity granularity, Instant bucketStart, RollupAggregate agg) {
        if (agg.isEmpty()) {
            log.debug("skip empty bucket: meter={} g={} t={}", meter.meterId(), granularity, bucketStart);
            return;
        }
        switch (granularity) {
            case HOUR -> hourlyRepo.upsert(
                meter.meterId(), meter.orgNodeId(),
                bucketStart.atOffset(ZoneOffset.UTC),
                agg.sum(), agg.avg(), agg.max(), agg.min(), agg.count());
            case DAY -> {
                LocalDate d = bucketStart.atOffset(ZoneOffset.UTC).toLocalDate();
                dailyRepo.upsert(meter.meterId(), meter.orgNodeId(), d,
                    agg.sum(), agg.avg(), agg.max(), agg.min(), agg.count());
            }
            case MONTH -> {
                String ym = YearMonth.from(bucketStart.atOffset(ZoneOffset.UTC)).toString();
                monthlyRepo.upsert(meter.meterId(), meter.orgNodeId(), ym,
                    agg.sum(), agg.avg(), agg.max(), agg.min(), agg.count());
            }
            case MINUTE -> throw new IllegalArgumentException("MINUTE 不写 rollup");
        }
    }

    private RollupAggregate aggregateRaw(String meterCode, TimeRange window) {
        String flux = FluxQueryBuilder.rawPointsForMeter(props.getBucket(), props.getMeasurement(), meterCode, window);
        RollupAggregate agg = RollupAggregate.empty();
        for (FluxTable t : influx.getQueryApi().query(flux, props.getOrg())) {
            for (FluxRecord r : t.getRecords()) {
                Object v = r.getValue();
                if (v instanceof Number n) {
                    agg = agg.add(n.doubleValue());
                }
            }
        }
        return agg;
    }

    @Transactional
    protected void recordFailure(Granularity granularity, Instant bucketStart, Long meterId, String err) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime bucketOdt = bucketStart.atOffset(ZoneOffset.UTC);
        String g = granularityKey(granularity);
        Optional<RollupJobFailure> existing = failureRepo.findActive(g, bucketOdt, meterId);
        RollupJobFailure f = existing.orElseGet(RollupJobFailure::new);
        if (existing.isEmpty()) {
            f.setGranularity(g);
            f.setBucketTs(bucketOdt);
            f.setMeterId(meterId);
            f.setAttempt(1);
        } else {
            int next = f.getAttempt() + 1;
            if (f.getAttempt() >= 3) {
                f.setAbandoned(true);
                f.setLastError(truncate(err));
                failureRepo.save(f);
                return;
            }
            f.setAttempt(next);
        }
        f.setLastError(truncate(err));
        long backoff = BACKOFF_SECONDS[Math.min(f.getAttempt() - 1, BACKOFF_SECONDS.length - 1)];
        f.setNextRetryAt(now.plusSeconds(backoff));
        failureRepo.save(f);
    }

    @Transactional
    protected void clearFailure(Granularity granularity, Instant bucketStart, Long meterId) {
        failureRepo.findActive(granularityKey(granularity), bucketStart.atOffset(ZoneOffset.UTC), meterId)
            .ifPresent(failureRepo::delete);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    static String granularityKey(Granularity g) {
        return switch (g) {
            case HOUR -> "HOURLY";
            case DAY -> "DAILY";
            case MONTH -> "MONTHLY";
            case MINUTE -> throw new IllegalArgumentException("MINUTE 无 rollup key");
        };
    }

    /* 用于 controller / batch 调用方的便捷方法。 */
    public List<Granularity> supportedGranularities() {
        return List.of(Granularity.HOUR, Granularity.DAY, Granularity.MONTH);
    }
}
