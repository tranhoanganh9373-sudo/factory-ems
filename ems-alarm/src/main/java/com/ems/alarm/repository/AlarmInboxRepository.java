package com.ems.alarm.repository;

import com.ems.alarm.entity.AlarmInbox;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlarmInboxRepository extends JpaRepository<AlarmInbox, Long> {
    long countByUserIdAndReadAtIsNull(Long userId);
    Page<AlarmInbox> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
