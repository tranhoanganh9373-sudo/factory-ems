package com.ems.cost.service.impl;

import com.ems.cost.service.MeterUsageReader;
import com.ems.timeseries.rollup.entity.RollupHourly;
import com.ems.timeseries.rollup.repository.RollupHourlyRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 把 ts_rollup_hourly 暴露为 cost 域的 MeterUsageReader 端口。 */
@Component
public class MeterUsageReaderImpl implements MeterUsageReader {

    private final RollupHourlyRepository rollupHourlyRepository;

    public MeterUsageReaderImpl(RollupHourlyRepository rollupHourlyRepository) {
        this.rollupHourlyRepository = rollupHourlyRepository;
    }

    @Override
    public List<HourlyUsage> hourly(Long meterId, OffsetDateTime start, OffsetDateTime end) {
        List<RollupHourly> rows = rollupHourlyRepository.findInRange(List.of(meterId), start, end);
        List<HourlyUsage> out = new ArrayList<>(rows.size());
        for (RollupHourly r : rows) {
            out.add(new HourlyUsage(r.getHourTs(), r.getSumValue()));
        }
        return out;
    }

    @Override
    public BigDecimal totalUsage(Long meterId, OffsetDateTime start, OffsetDateTime end) {
        List<RollupHourly> rows = rollupHourlyRepository.findInRange(List.of(meterId), start, end);
        BigDecimal sum = BigDecimal.ZERO;
        for (RollupHourly r : rows) sum = sum.add(r.getSumValue());
        return sum;
    }

    @Override
    public Map<Long, BigDecimal> totalUsageBatch(List<Long> meterIds, OffsetDateTime start, OffsetDateTime end) {
        if (meterIds == null || meterIds.isEmpty()) return Map.of();
        List<RollupHourly> rows = rollupHourlyRepository.findInRange(meterIds, start, end);
        Map<Long, BigDecimal> out = new HashMap<>();
        for (RollupHourly r : rows) {
            out.merge(r.getMeterId(), r.getSumValue(), BigDecimal::add);
        }
        return out;
    }
}
