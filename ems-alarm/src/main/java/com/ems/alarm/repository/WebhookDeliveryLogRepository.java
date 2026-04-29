package com.ems.alarm.repository;

import com.ems.alarm.entity.WebhookDeliveryLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLog, Long> {
    Page<WebhookDeliveryLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
