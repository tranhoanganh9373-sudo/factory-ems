package com.ems.audit.repository;

import com.ems.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    // 使用 native + CAST 避免 PostgreSQL 推断不出空参数类型 (ERROR: could not determine data type of parameter)
    @Query(value = """
        SELECT * FROM audit_logs
        WHERE (CAST(:actor AS bigint) IS NULL OR actor_user_id = :actor)
          AND (CAST(:resType AS varchar) IS NULL OR resource_type = :resType)
          AND (CAST(:action AS varchar) IS NULL OR action = :action)
          AND (CAST(:fromTs AS timestamptz) IS NULL OR occurred_at >= :fromTs)
          AND (CAST(:toTs AS timestamptz) IS NULL OR occurred_at < :toTs)
        ORDER BY occurred_at DESC
        """,
        countQuery = """
        SELECT count(*) FROM audit_logs
        WHERE (CAST(:actor AS bigint) IS NULL OR actor_user_id = :actor)
          AND (CAST(:resType AS varchar) IS NULL OR resource_type = :resType)
          AND (CAST(:action AS varchar) IS NULL OR action = :action)
          AND (CAST(:fromTs AS timestamptz) IS NULL OR occurred_at >= :fromTs)
          AND (CAST(:toTs AS timestamptz) IS NULL OR occurred_at < :toTs)
        """,
        nativeQuery = true)
    Page<AuditLog> search(@Param("actor") Long actor,
                          @Param("resType") String resType,
                          @Param("action") String action,
                          @Param("fromTs") OffsetDateTime from,
                          @Param("toTs") OffsetDateTime to,
                          Pageable pageable);
}
