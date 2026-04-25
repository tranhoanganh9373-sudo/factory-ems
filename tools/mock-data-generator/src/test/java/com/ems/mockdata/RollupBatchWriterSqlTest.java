package com.ems.mockdata;

import com.ems.mockdata.timeseries.RollupBatchWriter;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates RollupBatchWriter.flushAll() ON CONFLICT upsert idempotency.
 * Disabled by default — requires running PG + already-seeded MOCK data.
 * Validation actually happens in Phase H via SanityChecker against live data.
 */
@Disabled("Requires running PG with mock seed; verified end-to-end in Phase H")
@SpringBootTest
@ActiveProfiles("mock")
@Testcontainers
class RollupBatchWriterSqlTest {

    @Autowired
    RollupBatchWriter writer;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void upsertHourly_idempotent() {
        // find a real meter_id and org_node_id from seeds
        Long meterId = jdbc.queryForObject(
            "SELECT id FROM meters WHERE code = 'MOCK-M-ELEC-MAIN' LIMIT 1", Long.class);
        Long orgNodeId = jdbc.queryForObject(
            "SELECT org_node_id FROM meters WHERE code = 'MOCK-M-ELEC-MAIN' LIMIT 1", Long.class);

        if (meterId == null) {
            // No seed data in test DB — skip
            return;
        }

        OffsetDateTime hourTs = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0,
            ZoneOffset.ofHours(8));

        RollupBatchWriter.HourlyRow row = new RollupBatchWriter.HourlyRow(
            meterId, orgNodeId, hourTs, 300.0, 5.0, 8.0, 2.0, 60);

        writer.addHourly(row);
        writer.flushAll();

        Long count1 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ts_rollup_hourly WHERE meter_id = ? AND hour_ts = ?",
            Long.class, meterId,
            java.sql.Timestamp.from(hourTs.toInstant()));
        assertThat(count1).isEqualTo(1L);

        // insert same row again -> should still be 1 (upsert)
        writer.addHourly(row);
        writer.flushAll();

        Long count2 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ts_rollup_hourly WHERE meter_id = ? AND hour_ts = ?",
            Long.class, meterId,
            java.sql.Timestamp.from(hourTs.toInstant()));
        assertThat(count2).isEqualTo(1L);
    }
}
