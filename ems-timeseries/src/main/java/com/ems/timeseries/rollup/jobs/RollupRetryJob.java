package com.ems.timeseries.rollup.jobs;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.rollup.MeterCatalogPort;
import com.ems.timeseries.rollup.RollupComputeService;
import com.ems.timeseries.rollup.RollupComputeService.MeterCtx;
import com.ems.timeseries.rollup.entity.RollupJobFailure;
import com.ems.timeseries.rollup.repository.RollupJobFailureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 每分钟扫描 rollup_job_failures 中 next_retry_at <= now 且 abandoned=false 的记录，逐条重算。
 * 成功 → 由 computeBucket 自动清除失败行；失败 → 由 computeBucket 推进 attempt + 退避；attempt=3 后下一次失败标记 abandoned。
 */
@Component
public class RollupRetryJob {

    private static final Logger log = LoggerFactory.getLogger(RollupRetryJob.class);

    private final RollupComputeService compute;
    private final RollupJobFailureRepository failureRepo;
    private final MeterCatalogPort meters;
    private final boolean enabled;

    public RollupRetryJob(RollupComputeService compute,
                          RollupJobFailureRepository failureRepo,
                          MeterCatalogPort meters,
                          @Value("${ems.rollup.retry.enabled:true}") boolean enabled) {
        this.compute = compute;
        this.failureRepo = failureRepo;
        this.meters = meters;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${ems.rollup.retry.cron:0 * * * * *}")
    public void run() {
        if (!enabled) return;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<RollupJobFailure> due = failureRepo.findDueForRetry(now);
        if (due.isEmpty()) return;
        log.info("rollup retry: {} due", due.size());
        int ok = 0, again = 0, skipped = 0;
        for (RollupJobFailure f : due) {
            Granularity g = parseGranularity(f.getGranularity());
            if (g == null || f.getMeterId() == null) { skipped++; continue; }
            Optional<MeterCtx> ctx = meters.findById(f.getMeterId());
            if (ctx.isEmpty()) { skipped++; continue; }
            boolean success = compute.computeBucket(ctx.get(), g, f.getBucketTs().toInstant());
            if (success) ok++; else again++;
        }
        log.info("rollup retry done: ok={} again={} skipped={}", ok, again, skipped);
    }

    private static Granularity parseGranularity(String s) {
        return switch (s) {
            case "HOURLY"  -> Granularity.HOUR;
            case "DAILY"   -> Granularity.DAY;
            case "MONTHLY" -> Granularity.MONTH;
            default -> null;
        };
    }
}
