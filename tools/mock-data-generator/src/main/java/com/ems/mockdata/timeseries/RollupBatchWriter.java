package com.ems.mockdata.timeseries;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Upserts ts_rollup_hourly / ts_rollup_daily / ts_rollup_monthly via JdbcTemplate batchUpdate.
 * Uses ON CONFLICT (meter_id, hour_ts) DO UPDATE for idempotency.
 * Batch size: 1000 rows per executeBatch.
 */
@Component
public class RollupBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(RollupBatchWriter.class);
    private static final int BATCH_SIZE = 1000;
    private static final int SCALE = 6;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    private static final String UPSERT_HOURLY = """
        INSERT INTO ts_rollup_hourly
            (meter_id, org_node_id, hour_ts, sum_value, avg_value, max_value, min_value, count, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (meter_id, hour_ts) DO UPDATE SET
            org_node_id = EXCLUDED.org_node_id,
            sum_value   = EXCLUDED.sum_value,
            avg_value   = EXCLUDED.avg_value,
            max_value   = EXCLUDED.max_value,
            min_value   = EXCLUDED.min_value,
            count       = EXCLUDED.count,
            updated_at  = now()
        """;

    private static final String UPSERT_DAILY = """
        INSERT INTO ts_rollup_daily
            (meter_id, org_node_id, day_date, sum_value, avg_value, max_value, min_value, count, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (meter_id, day_date) DO UPDATE SET
            org_node_id = EXCLUDED.org_node_id,
            sum_value   = EXCLUDED.sum_value,
            avg_value   = EXCLUDED.avg_value,
            max_value   = EXCLUDED.max_value,
            min_value   = EXCLUDED.min_value,
            count       = EXCLUDED.count,
            updated_at  = now()
        """;

    private static final String UPSERT_MONTHLY = """
        INSERT INTO ts_rollup_monthly
            (meter_id, org_node_id, year_month, sum_value, avg_value, max_value, min_value, count, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (meter_id, year_month) DO UPDATE SET
            org_node_id = EXCLUDED.org_node_id,
            sum_value   = EXCLUDED.sum_value,
            avg_value   = EXCLUDED.avg_value,
            max_value   = EXCLUDED.max_value,
            min_value   = EXCLUDED.min_value,
            count       = EXCLUDED.count,
            updated_at  = now()
        """;

    private final JdbcTemplate jdbc;

    // in-memory accumulators
    public record HourlyRow(long meterId, long orgNodeId, OffsetDateTime hourTs,
                            double sum, double avg, double max, double min, int count) {}

    public record DailyRow(long meterId, long orgNodeId, LocalDate dayDate,
                           double sum, double avg, double max, double min, int count) {}

    public record MonthlyRow(long meterId, long orgNodeId, String yearMonth,
                             double sum, double avg, double max, double min, int count) {}

    private final List<HourlyRow> hourlyBuffer  = new ArrayList<>(BATCH_SIZE + 1);
    private final List<DailyRow>  dailyBuffer   = new ArrayList<>(BATCH_SIZE + 1);
    private final List<MonthlyRow> monthlyBuffer = new ArrayList<>(BATCH_SIZE + 1);

    public RollupBatchWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void addHourly(HourlyRow row) {
        hourlyBuffer.add(row);
        if (hourlyBuffer.size() >= BATCH_SIZE) flushHourly();
    }

    public void addDaily(DailyRow row) {
        dailyBuffer.add(row);
        if (dailyBuffer.size() >= BATCH_SIZE) flushDaily();
    }

    public void addMonthly(MonthlyRow row) {
        monthlyBuffer.add(row);
        if (monthlyBuffer.size() >= BATCH_SIZE) flushMonthly();
    }

    public void flushAll() {
        flushHourly();
        flushDaily();
        flushMonthly();
    }

    private void flushHourly() {
        if (hourlyBuffer.isEmpty()) return;
        log.debug("Flushing {} hourly rows", hourlyBuffer.size());
        List<Object[]> params = new ArrayList<>(hourlyBuffer.size());
        for (HourlyRow r : hourlyBuffer) {
            params.add(new Object[]{
                r.meterId(), r.orgNodeId(),
                Timestamp.from(r.hourTs().toInstant()),
                bd(r.sum()), bd(r.avg()), bd(r.max()), bd(r.min()),
                r.count()
            });
        }
        jdbc.batchUpdate(UPSERT_HOURLY, params);
        hourlyBuffer.clear();
    }

    private void flushDaily() {
        if (dailyBuffer.isEmpty()) return;
        log.debug("Flushing {} daily rows", dailyBuffer.size());
        List<Object[]> params = new ArrayList<>(dailyBuffer.size());
        for (DailyRow r : dailyBuffer) {
            params.add(new Object[]{
                r.meterId(), r.orgNodeId(),
                java.sql.Date.valueOf(r.dayDate()),
                bd(r.sum()), bd(r.avg()), bd(r.max()), bd(r.min()),
                r.count()
            });
        }
        jdbc.batchUpdate(UPSERT_DAILY, params);
        dailyBuffer.clear();
    }

    private void flushMonthly() {
        if (monthlyBuffer.isEmpty()) return;
        log.debug("Flushing {} monthly rows", monthlyBuffer.size());
        List<Object[]> params = new ArrayList<>(monthlyBuffer.size());
        for (MonthlyRow r : monthlyBuffer) {
            params.add(new Object[]{
                r.meterId(), r.orgNodeId(),
                r.yearMonth(),
                bd(r.sum()), bd(r.avg()), bd(r.max()), bd(r.min()),
                r.count()
            });
        }
        jdbc.batchUpdate(UPSERT_MONTHLY, params);
        monthlyBuffer.clear();
    }

    private BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(SCALE, RM);
    }
}
