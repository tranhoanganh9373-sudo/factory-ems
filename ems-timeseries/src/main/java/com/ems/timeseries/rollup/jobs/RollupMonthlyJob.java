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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 每月 1 日 01:00 UTC：处理上月桶 + 兜底再算上上月（覆盖跨月延迟）。
 */
@Component
public class RollupMonthlyJob {

    private static final Logger log = LoggerFactory.getLogger(RollupMonthlyJob.class);

    private final RollupComputeService compute;
    private final MeterCatalogPort meters;
    private final long lookbackMonths;
    private final boolean enabled;

    public RollupMonthlyJob(RollupComputeService compute, MeterCatalogPort meters,
                            @Value("${ems.rollup.monthly.lookback-months:2}") long lookbackMonths,
                            @Value("${ems.rollup.monthly.enabled:true}") boolean enabled) {
        this.compute = compute;
        this.meters = meters;
        this.lookbackMonths = lookbackMonths;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${ems.rollup.monthly.cron:0 0 1 1 * *}", zone = "UTC")
    public void run() {
        if (!enabled) return;
        YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);
        List<MeterCtx> all = meters.findAllEnabled();
        log.debug("monthly rollup: {} meters, lookback {} months from {}", all.size(), lookbackMonths, thisMonth);
        int ok = 0, fail = 0;
        for (long i = 1; i <= lookbackMonths; i++) {
            YearMonth target = thisMonth.minusMonths(i);
            Instant t = target.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            for (MeterCtx m : all) {
                if (compute.computeBucket(m, Granularity.MONTH, t)) ok++; else fail++;
            }
        }
        if (fail > 0) log.warn("monthly rollup done: ok={} fail={}", ok, fail);
    }
}
