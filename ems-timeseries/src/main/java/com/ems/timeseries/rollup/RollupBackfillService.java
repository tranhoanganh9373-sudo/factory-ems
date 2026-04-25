package com.ems.timeseries.rollup;

import com.ems.audit.annotation.Audited;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.rollup.RollupComputeService.MeterCtx;
import com.ems.timeseries.rollup.dto.BackfillReq;
import com.ems.timeseries.rollup.dto.BackfillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class RollupBackfillService {

    private static final Logger log = LoggerFactory.getLogger(RollupBackfillService.class);

    private final RollupComputeService compute;
    private final MeterCatalogPort meters;

    public RollupBackfillService(RollupComputeService compute, MeterCatalogPort meters) {
        this.compute = compute;
        this.meters = meters;
    }

    @Audited(action = "REBUILD", resourceType = "ROLLUP", resourceIdExpr = "#req.granularity().name()")
    public BackfillResult rebuild(BackfillReq req) {
        if (req.granularity() == Granularity.MINUTE) {
            throw new IllegalArgumentException("MINUTE 粒度不参与 rollup");
        }
        if (!req.to().isAfter(req.from())) {
            throw new IllegalArgumentException("to 必须晚于 from");
        }

        List<MeterCtx> targets = resolveMeters(req.meterIds());
        Instant from = BucketWindow.truncate(req.granularity(), req.from());
        Instant to   = BucketWindow.truncate(req.granularity(), req.to());

        int ok = 0, fail = 0, buckets = 0;
        for (MeterCtx m : targets) {
            for (Instant t = from; !t.isAfter(to); t = stepForward(req.granularity(), t)) {
                if (m == targets.get(0)) buckets++; // 桶数只算一遍
                if (compute.computeBucket(m, req.granularity(), t)) ok++; else fail++;
            }
        }
        log.info("rollup backfill done: granularity={} meters={} buckets={} ok={} fail={}",
            req.granularity(), targets.size(), buckets, ok, fail);
        return new BackfillResult(targets.size(), buckets, ok, fail);
    }

    private List<MeterCtx> resolveMeters(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return meters.findAllEnabled();
        List<MeterCtx> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            meters.findById(id).ifPresent(out::add);
        }
        return out;
    }

    private static Instant stepForward(Granularity g, Instant t) {
        return switch (g) {
            case HOUR  -> t.plus(1, ChronoUnit.HOURS);
            case DAY   -> t.plus(1, ChronoUnit.DAYS);
            case MONTH -> YearMonth.from(t.atOffset(ZoneOffset.UTC)).plusMonths(1)
                .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            case MINUTE -> throw new IllegalArgumentException("MINUTE 不参与 rollup");
        };
    }
}
