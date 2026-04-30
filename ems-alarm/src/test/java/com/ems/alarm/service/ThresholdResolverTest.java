package com.ems.alarm.service;

import com.ems.alarm.config.AlarmProperties;
import com.ems.alarm.entity.AlarmRuleOverride;
import com.ems.alarm.repository.AlarmRuleOverrideRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ThresholdResolverTest {

    private final AlarmProperties props = new AlarmProperties(600, 3, 60, 300, 3, List.of(10, 60, 300), 5000);
    private final AlarmRuleOverrideRepository repo = mock(AlarmRuleOverrideRepository.class);
    private final ThresholdResolver resolver = new ThresholdResolver(props, repo);

    @Test
    void noOverride_usesGlobalDefaults() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        ThresholdResolver.Resolved r = resolver.resolve(1L);
        assertThat(r.silentTimeoutSeconds()).isEqualTo(600);
        assertThat(r.consecutiveFailCount()).isEqualTo(3);
        assertThat(r.maintenanceMode()).isFalse();
    }

    @Test
    void override_takesPrecedenceOverDefault() {
        AlarmRuleOverride o = new AlarmRuleOverride();
        o.setDeviceId(1L);
        o.setSilentTimeoutSeconds(120);
        o.setConsecutiveFailCount(5);
        o.setMaintenanceMode(true);
        when(repo.findById(1L)).thenReturn(Optional.of(o));

        ThresholdResolver.Resolved r = resolver.resolve(1L);
        assertThat(r.silentTimeoutSeconds()).isEqualTo(120);
        assertThat(r.consecutiveFailCount()).isEqualTo(5);
        assertThat(r.maintenanceMode()).isTrue();
    }

    @Test
    void partialOverride_fallsBackPerField() {
        AlarmRuleOverride o = new AlarmRuleOverride();
        o.setDeviceId(1L);
        o.setSilentTimeoutSeconds(120);
        o.setConsecutiveFailCount(null);  // not set → fall back to global
        when(repo.findById(1L)).thenReturn(Optional.of(o));

        ThresholdResolver.Resolved r = resolver.resolve(1L);
        assertThat(r.silentTimeoutSeconds()).isEqualTo(120);
        assertThat(r.consecutiveFailCount()).isEqualTo(3);  // global
    }
}
