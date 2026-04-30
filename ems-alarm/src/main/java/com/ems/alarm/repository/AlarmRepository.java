package com.ems.alarm.repository;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    @Query("""
        SELECT a FROM Alarm a
        WHERE a.deviceId = :deviceId AND a.alarmType = :type
          AND a.status IN (com.ems.alarm.entity.AlarmStatus.ACTIVE,
                           com.ems.alarm.entity.AlarmStatus.ACKED)
        ORDER BY a.triggeredAt DESC
        """)
    Optional<Alarm> findActive(@Param("deviceId") Long deviceId, @Param("type") AlarmType type);

    long countByStatus(AlarmStatus status);

    /** ACTIVE + ACKED 计数（按 type 分），用于 {@code ems.alarm.active.count{type}} gauge。 */
    @Query("""
        SELECT COUNT(a) FROM Alarm a
        WHERE a.alarmType = :type
          AND a.status IN (com.ems.alarm.entity.AlarmStatus.ACTIVE,
                           com.ems.alarm.entity.AlarmStatus.ACKED)
        """)
    long countActiveByType(@Param("type") AlarmType type);

    @Query("""
        SELECT a FROM Alarm a
        WHERE (:status IS NULL OR a.status = :status)
          AND (:deviceId IS NULL OR a.deviceId = :deviceId)
          AND (:type IS NULL OR a.alarmType = :type)
          AND (:from IS NULL OR a.triggeredAt >= :from)
          AND (:to IS NULL OR a.triggeredAt < :to)
        """)
    Page<Alarm> search(@Param("status") AlarmStatus status,
                       @Param("deviceId") Long deviceId,
                       @Param("type") AlarmType type,
                       @Param("from") OffsetDateTime from,
                       @Param("to") OffsetDateTime to,
                       Pageable pageable);

    List<Alarm> findTop10ByOrderByTriggeredAtDesc();
}
