package com.ems.audit.repository;

import com.ems.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:actor IS NULL OR a.actorUserId = :actor)
          AND (:resType IS NULL OR a.resourceType = :resType)
          AND (:action IS NULL OR a.action = :action)
          AND (:from IS NULL OR a.occurredAt >= :from)
          AND (:to IS NULL OR a.occurredAt < :to)
        ORDER BY a.occurredAt DESC
    """)
    Page<AuditLog> search(@Param("actor") Long actor,
                          @Param("resType") String resType,
                          @Param("action") String action,
                          @Param("from") OffsetDateTime from,
                          @Param("to") OffsetDateTime to,
                          Pageable pageable);
}
