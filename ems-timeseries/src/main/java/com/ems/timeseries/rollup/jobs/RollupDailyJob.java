package com.ems.timeseries.rollup.jobs;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.rollup.MeterCatalogPort;
import com.ems.timeseries.rollup.RollupComputeService;
import com.ems.timeseries.rollup.RollupComputeService.MeterCtx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 每日 00:30 UTC 跑：处理 [今日-lookbackDays, 今日-1day] 的每日桶。幂等。
 */
@Component
public class RollupDailyJob {

    private static final Logger log = LoggerFactory.getLogger(RollupDailyJob.class);

    private final RollupComputeService compute;
    private final MeterCatalogPort meters;
    private final long lookbackDays;
    private final boolean enabled;

    public RollupDailyJob(RollupComputeService compute, MeterCatalogPort meters,
                          @Value("${ems.rollup.daily.lookback-days:7}") long lookbackDays,
                          @Value("${ems.rollup.daily.enabled:true}") boolean enabled) {
        this.compute = compute;
        this.meters = meters;
        this.lookbackDays = lookbackDays;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${ems.rollup.daily.cron:0 30 0 * * *}", zone = "UTC")
    public void run() {
        if (!enabled) return;
        Instant todayStart = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant ceiling = todayStart.minus(1, ChronoUnit.DAYS);
        Instant floor   = todayStart.minus(lookbackDays, ChronoUnit.DAYS);
        List<MeterCtx> all = meters.findAllEnabled();
        log.debug("daily rollup: {} meters, window [{} → {}]", all.size(), floor, ceiling);
        int ok = 0, fail = 0;
        for (MeterCtx m : all) {
            for (Instant t = floor; !t.isAfter(ceiling); t = t.plus(1, ChronoUnit.DAYS)) {
                if (compute.computeBucket(m, Granularity.DAY, t)) ok++; else fail++;
            }
        }
        if (fail > 0) log.warn("daily rollup done: ok={} fail={}", ok, fail);
    }
}
