package com.ems.alarm.service;

import com.ems.alarm.config.AlarmProperties;
import com.ems.alarm.entity.AlarmRuleOverride;
import com.ems.alarm.repository.AlarmRuleOverrideRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ThresholdResolver {
    private final AlarmProperties props;
    private final AlarmRuleOverrideRepository repo;

    public ThresholdResolver(AlarmProperties props, AlarmRuleOverrideRepository repo) {
        this.props = props;
        this.repo = repo;
    }

    public Resolved resolve(Long deviceId) {
        Optional<AlarmRuleOverride> ov = repo.findById(deviceId);
        int silent = ov.map(AlarmRuleOverride::getSilentTimeoutSeconds)
                       .filter(v -> v != null)
                       .orElse(props.defaultSilentTimeoutSeconds());
        int fail   = ov.map(AlarmRuleOverride::getConsecutiveFailCount)
                       .filter(v -> v != null)
                       .orElse(props.defaultConsecutiveFailCount());
        boolean maint = ov.map(AlarmRuleOverride::isMaintenanceMode).orElse(false);
        return new Resolved(silent, fail, maint);
    }

    public record Resolved(int silentTimeoutSeconds, int consecutiveFailCount, boolean maintenanceMode) {}
}
