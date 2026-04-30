package com.ems.alarm.repository;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AlarmRepository extends JpaRepository<Alarm, Long>, JpaSpecificationExecutor<Alarm> {

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

    List<Alarm> findTop10ByOrderByTriggeredAtDesc();
}
