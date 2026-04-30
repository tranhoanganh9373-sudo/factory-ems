package com.ems.alarm.repository;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlarmRepository 集成测试（Testcontainers + PostgreSQL）。
 *
 * <p>本地 macOS Docker Desktop + docker-java 兼容问题会导致 Testcontainers 找不到合法的 Docker 环境。
 * CI 中（Linux + 完整 Docker 引擎）应删除 {@code @Disabled} 让本测试自动运行。
 */
@Disabled("Testcontainers + Docker Desktop 本地兼容问题，CI 环境删除此注解即可")
@SpringBootTest(classes = AlarmRepositoryIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class AlarmRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    AlarmRepository alarms;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void truncate() {
        jdbc.execute("TRUNCATE TABLE alarms RESTART IDENTITY CASCADE");
    }

    // -------------------------------------------------------------------------
    // findActive
    // -------------------------------------------------------------------------

    @Test
    void findActive_returnsActive_excludesResolved() {
        OffsetDateTime base = OffsetDateTime.now();
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.RESOLVED, base.minusHours(3)));
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACKED,   base.minusHours(2)));
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE,  base.minusHours(1)));

        Optional<Alarm> result = alarms.findActive(1L, AlarmType.SILENT_TIMEOUT);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isIn(AlarmStatus.ACTIVE, AlarmStatus.ACKED);
        assertThat(result.get().getStatus()).isNotEqualTo(AlarmStatus.RESOLVED);
    }

    @Test
    void findActive_noMatchingDevice_returnsEmpty() {
        alarms.saveAndFlush(newAlarm(2L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, OffsetDateTime.now()));

        Optional<Alarm> result = alarms.findActive(1L, AlarmType.SILENT_TIMEOUT);

        assertThat(result).isEmpty();
    }

    @Test
    void findActive_noMatchingType_returnsEmpty() {
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, OffsetDateTime.now()));

        Optional<Alarm> result = alarms.findActive(1L, AlarmType.CONSECUTIVE_FAIL);

        assertThat(result).isEmpty();
    }

    @Test
    void findActive_multipleActive_returnsMostRecent() {
        OffsetDateTime older = OffsetDateTime.now().minusHours(2);
        OffsetDateTime newer = OffsetDateTime.now().minusHours(1);
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, older));
        Alarm newerAlarm = alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, newer));

        Optional<Alarm> result = alarms.findActive(1L, AlarmType.SILENT_TIMEOUT);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(newerAlarm.getId());
    }

    // -------------------------------------------------------------------------
    // search
    // -------------------------------------------------------------------------

    @Test
    void search_byStatus_filtersOutOthers() {
        OffsetDateTime base = OffsetDateTime.now();
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE,   base.minusMinutes(4)));
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE,   base.minusMinutes(3)));
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.RESOLVED, base.minusMinutes(2)));
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.RESOLVED, base.minusMinutes(1)));

        Page<Alarm> page = alarms.search(AlarmStatus.ACTIVE, null, null, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(a -> a.getStatus() == AlarmStatus.ACTIVE);
    }

    @Test
    void search_byDeviceId_filters() {
        OffsetDateTime base = OffsetDateTime.now();
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, base.minusMinutes(3)));
        alarms.saveAndFlush(newAlarm(2L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, base.minusMinutes(2)));
        alarms.saveAndFlush(newAlarm(3L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, base.minusMinutes(1)));

        Page<Alarm> page = alarms.search(null, 2L, null, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getDeviceId()).isEqualTo(2L);
    }

    @Test
    void search_byTimeRange_filters() {
        OffsetDateTime t1 = OffsetDateTime.now().minusMinutes(3);
        OffsetDateTime t2 = OffsetDateTime.now().minusMinutes(2);
        OffsetDateTime t3 = OffsetDateTime.now().minusMinutes(1);
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, t1));
        Alarm t2Alarm = alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, t2));
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, t3));

        // from=t2 (inclusive), to=t3 (exclusive) → only the t2 row
        Page<Alarm> page = alarms.search(null, null, null, t2, t3, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(t2Alarm.getId());
    }

    @Test
    void search_paging_respectsPageable() {
        OffsetDateTime base = OffsetDateTime.now();
        for (int i = 0; i < 5; i++) {
            alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, base.minusMinutes(i + 1)));
        }

        Page<Alarm> page = alarms.search(null, null, null, null, null, PageRequest.of(1, 2));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getNumber()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // countByStatus
    // -------------------------------------------------------------------------

    @Test
    void countByStatus_returnsCorrectCount() {
        OffsetDateTime base = OffsetDateTime.now();
        for (int i = 0; i < 3; i++) {
            alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE,   base.minusMinutes(i + 10)));
        }
        alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACKED,    base.minusMinutes(7)));
        for (int i = 0; i < 2; i++) {
            alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.RESOLVED, base.minusMinutes(i + 4)));
        }

        assertThat(alarms.countByStatus(AlarmStatus.ACTIVE)).isEqualTo(3);
        assertThat(alarms.countByStatus(AlarmStatus.ACKED)).isEqualTo(1);
        assertThat(alarms.countByStatus(AlarmStatus.RESOLVED)).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // findTop10ByOrderByTriggeredAtDesc
    // -------------------------------------------------------------------------

    @Test
    void findTop10ByOrderByTriggeredAtDesc_returnsAtMost10_orderedDesc() {
        OffsetDateTime base = OffsetDateTime.now();
        // Insert 12 alarms: index 0 = oldest (minusMinutes(12)), index 11 = newest (minusMinutes(1))
        for (int i = 0; i < 12; i++) {
            alarms.saveAndFlush(newAlarm(1L, AlarmType.SILENT_TIMEOUT, AlarmStatus.ACTIVE, base.minusMinutes(12 - i)));
        }

        List<Alarm> top10 = alarms.findTop10ByOrderByTriggeredAtDesc();

        assertThat(top10).hasSize(10);
        // Verify strict descending order throughout the list
        for (int i = 0; i < top10.size() - 1; i++) {
            assertThat(top10.get(i).getTriggeredAt()).isAfter(top10.get(i + 1).getTriggeredAt());
        }
        // First should be the newest (minusMinutes(1)), last should be the 10th most recent (minusMinutes(10))
        assertThat(top10.get(0).getTriggeredAt()).isAfter(top10.get(9).getTriggeredAt());
        // The 2 oldest rows (minusMinutes(11) and minusMinutes(12)) must be absent
        OffsetDateTime eleventhOldest = base.minusMinutes(11);
        assertThat(top10).noneMatch(a -> !a.getTriggeredAt().isAfter(eleventhOldest));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private Alarm newAlarm(Long deviceId, AlarmType type, AlarmStatus status, OffsetDateTime triggeredAt) {
        Alarm a = new Alarm();
        a.setDeviceId(deviceId);
        a.setDeviceType("METER");
        a.setAlarmType(type);
        a.setStatus(status);
        a.setSeverity("NORMAL");
        a.setTriggeredAt(triggeredAt);
        a.setLastSeenAt(triggeredAt);
        return a;
    }

    // -------------------------------------------------------------------------
    // Inner TestApp configuration
    // -------------------------------------------------------------------------

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.ems.alarm.entity", "com.ems.meter.entity"})
    @EnableJpaRepositories(basePackages = {
            "com.ems.alarm.repository",
            "com.ems.meter.repository"
    })
    static class TestApp {
    }
}
