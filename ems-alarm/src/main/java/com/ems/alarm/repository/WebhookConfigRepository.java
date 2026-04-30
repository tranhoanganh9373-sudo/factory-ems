package com.ems.alarm.repository;

import com.ems.alarm.entity.WebhookConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, Long> {
    Optional<WebhookConfig> findFirstByOrderByIdAsc();
}
