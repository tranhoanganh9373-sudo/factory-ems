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
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 每 5 分钟扫描 [now - lookbackHours, now - 1h]，对每个 enabled meter 计算缺失的小时桶。
 * 幂等 upsert 保证多次运行无副作用。
 */
@Component
public class RollupHourlyJob {

    private static final Logger log = LoggerFactory.getLogger(RollupHourlyJob.class);

    private final RollupComputeService compute;
    private final MeterCatalogPort meters;
    private final long lookbackHours;
    private final boolean enabled;

    public RollupHourlyJob(RollupComputeService compute, MeterCatalogPort meters,
                           @Value("${ems.rollup.hourly.lookback-hours:24}") long lookbackHours,
                           @Value("${ems.rollup.hourly.enabled:true}") boolean enabled) {
        this.compute = compute;
        this.meters = meters;
        this.lookbackHours = lookbackHours;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${ems.rollup.hourly.cron:0 */5 * * * *}")
    public void run() {
        if (!enabled) return;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant ceiling = now.minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        Instant floor = ceiling.minus(lookbackHours - 1, ChronoUnit.HOURS);
        List<MeterCtx> all = meters.findAllEnabled();
        log.debug("hourly rollup: {} meters, window [{} → {}]", all.size(), floor, ceiling);
        int ok = 0, fail = 0;
        for (MeterCtx m : all) {
            for (Instant t = floor; !t.isAfter(ceiling); t = t.plus(1, ChronoUnit.HOURS)) {
                if (compute.computeBucket(m, Granularity.HOUR, t)) ok++; else fail++;
            }
        }
        if (fail > 0) log.warn("hourly rollup done: ok={} fail={}", ok, fail);
    }
}
