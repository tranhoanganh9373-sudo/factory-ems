package com.ems.mockdata.verify;

import com.ems.meter.repository.MeterRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Post-seed sanity checks:
 * 1. ts_rollup_hourly row count >= expected minimum
 * 2. Per parent meter: parent.sum_value vs sum(children.sum_value) within tolerance
 * 3. 24h max/min ratio in acceptable range
 * 4. Weekend/weekday mean ratio in [0.3, 0.7]
 * Returns false on any failure; logs all failures before returning.
 */
@Component
public class SanityChecker {

    private static final Logger log = LoggerFactory.getLogger(SanityChecker.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final MeterRepository meterRepo;
    private final MeterTopologyRepository topoRepo;

    public SanityChecker(JdbcTemplate jdbc, MeterRepository meterRepo,
                         MeterTopologyRepository topoRepo) {
        this.jdbc = jdbc;
        this.meterRepo = meterRepo;
        this.topoRepo = topoRepo;
    }

    public boolean check(LocalDate startDate, LocalDate endDate) {
        log.info("Running sanity checks for {} to {}", startDate, endDate);
        boolean pass = true;

        pass &= checkRowCount(startDate, endDate);
        pass &= checkConservation(startDate, endDate);
        pass &= checkDayNightRatio(startDate, endDate);
        pass &= checkWeekendWeekdayRatio(startDate, endDate);

        if (pass) {
            log.info("All sanity checks PASSED");
        } else {
            log.error("One or more sanity checks FAILED");
        }
        return pass;
    }

    private boolean checkRowCount(LocalDate start, LocalDate end) {
        OffsetDateTime startOdt = start.atStartOfDay(SHANGHAI).toOffsetDateTime();
        OffsetDateTime endOdt = end.atStartOfDay(SHANGHAI).toOffsetDateTime();

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ts_rollup_hourly WHERE hour_ts >= ? AND hour_ts < ?",
            Long.class,
            java.sql.Timestamp.from(startOdt.toInstant()),
            java.sql.Timestamp.from(endOdt.toInstant()));

        long meters = meterRepo.findAll().stream()
            .filter(m -> m.getCode().startsWith("MOCK-"))
            .count();
        long days = start.until(end).getDays();
        // each meter should have ~24 rows per day; allow 50% missing due to weekend/holidays
        long expected = (long) (meters * days * 24 * 0.5);

        boolean ok = count != null && count >= expected;
        if (ok) {
            log.info("CHECK row_count: {} rows >= {} expected [PASS]", count, expected);
        } else {
            log.error("CHECK row_count: {} rows < {} expected [FAIL]", count, expected);
        }
        return ok;
    }

    private boolean checkConservation(LocalDate start, LocalDate end) {
        List<Map<String, Object>> parents = jdbc.queryForList(
            "SELECT DISTINCT parent_meter_id FROM meter_topology");

        if (parents.isEmpty()) {
            log.info("CHECK conservation: no topology found, skipping [PASS]");
            return true;
        }

        OffsetDateTime startOdt = start.atStartOfDay(SHANGHAI).toOffsetDateTime();
        OffsetDateTime endOdt = end.atStartOfDay(SHANGHAI).toOffsetDateTime();

        boolean allOk = true;
        for (Map<String, Object> row : parents) {
            long parentId = ((Number) row.get("parent_meter_id")).longValue();

            Long violations = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT p.hour_ts,
                           p.sum_value as parent_val,
                           COALESCE(SUM(c.sum_value), 0) as children_sum
                    FROM ts_rollup_hourly p
                    LEFT JOIN meter_topology t ON t.parent_meter_id = p.meter_id
                    LEFT JOIN ts_rollup_hourly c
                        ON c.meter_id = t.child_meter_id AND c.hour_ts = p.hour_ts
                    WHERE p.meter_id = ?
                      AND p.hour_ts >= ? AND p.hour_ts < ?
                    GROUP BY p.hour_ts, p.sum_value
                    HAVING ABS(p.sum_value - SUM(c.sum_value)) / NULLIF(SUM(c.sum_value), 0) > 0.20
                ) v
                """,
                Long.class,
                parentId,
                java.sql.Timestamp.from(startOdt.toInstant()),
                java.sql.Timestamp.from(endOdt.toInstant()));

            Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ts_rollup_hourly WHERE meter_id = ? AND hour_ts >= ? AND hour_ts < ?",
                Long.class,
                parentId,
                java.sql.Timestamp.from(startOdt.toInstant()),
                java.sql.Timestamp.from(endOdt.toInstant()));

            if (total == null || total == 0) continue;
            double violationRate = violations == null ? 0.0 : (double) violations / total;
            // allow up to 5% violations (negative-residual injected hours)
            boolean ok = violationRate <= 0.05;
            String pct = String.format("%.4f", violationRate);
            if (ok) {
                log.info("CHECK conservation meter={}: violation_rate={} [PASS]", parentId, pct);
            } else {
                log.error("CHECK conservation meter={}: violation_rate={} > 5% [FAIL]", parentId, pct);
                allOk = false;
            }
        }
        return allOk;
    }

    private boolean checkDayNightRatio(LocalDate start, LocalDate end) {
        OffsetDateTime startOdt = start.atStartOfDay(SHANGHAI).toOffsetDateTime();
        OffsetDateTime endOdt = start.plusDays(7).atStartOfDay(SHANGHAI).toOffsetDateTime();

        Double dayAvg = jdbc.queryForObject("""
            SELECT AVG(sum_value) FROM ts_rollup_hourly h
            JOIN meters m ON m.id = h.meter_id
            WHERE m.code LIKE 'MOCK-M-ELEC-%'
              AND EXTRACT(HOUR FROM hour_ts AT TIME ZONE 'Asia/Shanghai') = 9
              AND hour_ts >= ? AND hour_ts < ?
            """, Double.class,
            java.sql.Timestamp.from(startOdt.toInstant()),
            java.sql.Timestamp.from(endOdt.toInstant()));

        Double nightAvg = jdbc.queryForObject("""
            SELECT AVG(sum_value) FROM ts_rollup_hourly h
            JOIN meters m ON m.id = h.meter_id
            WHERE m.code LIKE 'MOCK-M-ELEC-%'
              AND EXTRACT(HOUR FROM hour_ts AT TIME ZONE 'Asia/Shanghai') = 3
              AND hour_ts >= ? AND hour_ts < ?
            """, Double.class,
            java.sql.Timestamp.from(startOdt.toInstant()),
            java.sql.Timestamp.from(endOdt.toInstant()));

        if (dayAvg == null || nightAvg == null || nightAvg == 0.0) {
            log.warn("CHECK day_night_ratio: insufficient data, skipping");
            return true;
        }

        double ratio = dayAvg / nightAvg;
        boolean ok = ratio >= 1.5 && ratio <= 8.0;
        String ratioStr = String.format("%.2f", ratio);
        String dayStr = String.format("%.4f", dayAvg);
        String nightStr = String.format("%.4f", nightAvg);
        if (ok) {
            log.info("CHECK day_night_ratio: {} (day={} / night={}) [PASS]", ratioStr, dayStr, nightStr);
        } else {
            log.error("CHECK day_night_ratio: {} outside [1.5, 8.0] [FAIL]", ratioStr);
        }
        return ok;
    }

    private boolean checkWeekendWeekdayRatio(LocalDate start, LocalDate end) {
        Double weekdayAvg = jdbc.queryForObject("""
            SELECT AVG(sum_value) FROM ts_rollup_daily h
            JOIN meters m ON m.id = h.meter_id
            WHERE m.code LIKE 'MOCK-M-ELEC-%'
              AND EXTRACT(DOW FROM day_date) BETWEEN 1 AND 5
              AND day_date >= ? AND day_date < ?
            """, Double.class,
            java.sql.Date.valueOf(start),
            java.sql.Date.valueOf(end));

        Double weekendAvg = jdbc.queryForObject("""
            SELECT AVG(sum_value) FROM ts_rollup_daily h
            JOIN meters m ON m.id = h.meter_id
            WHERE m.code LIKE 'MOCK-M-ELEC-%'
              AND EXTRACT(DOW FROM day_date) IN (0, 6)
              AND day_date >= ? AND day_date < ?
            """, Double.class,
            java.sql.Date.valueOf(start),
            java.sql.Date.valueOf(end));

        if (weekdayAvg == null || weekendAvg == null || weekdayAvg == 0.0) {
            log.warn("CHECK weekend_ratio: insufficient data, skipping");
            return true;
        }

        double ratio = weekendAvg / weekdayAvg;
        boolean ok = ratio >= 0.3 && ratio <= 0.7;
        String ratioStr = String.format("%.2f", ratio);
        String weStr = String.format("%.4f", weekendAvg);
        String wdStr = String.format("%.4f", weekdayAvg);
        if (ok) {
            log.info("CHECK weekend_ratio: {} (weekend={} / weekday={}) [PASS]", ratioStr, weStr, wdStr);
        } else {
            log.error("CHECK weekend_ratio: {} outside [0.3, 0.7] [FAIL]", ratioStr);
        }
        return ok;
    }
}
