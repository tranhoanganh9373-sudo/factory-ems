package com.ems.alarm.repository;

import com.ems.alarm.entity.AlarmRuleOverride;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlarmRuleOverrideRepository extends JpaRepository<AlarmRuleOverride, Long> {
}
