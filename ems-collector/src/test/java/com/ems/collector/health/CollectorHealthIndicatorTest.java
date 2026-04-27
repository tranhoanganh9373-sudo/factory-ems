package com.ems.collector.health;

import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.poller.DeviceState;
import com.ems.collector.service.CollectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectorHealthIndicatorTest {

    private CollectorService svc;
    private CollectorHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        svc = mock(CollectorService.class);
        indicator = new CollectorHealthIndicator(svc);
    }

    @Test
    void notRunning_returnsUnknown() {
        when(svc.isRunning()).thenReturn(false);
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(h.getDetails()).containsEntry("disabled", true);
    }

    @Test
    void runningWithNoDevices_returnsUp() {
        when(svc.isRunning()).thenReturn(true);
        when(svc.snapshots()).thenReturn(List.of());
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("devices", 0);
    }

    @Test
    void allHealthy_isUp() {
        whenSnapshots(DeviceState.HEALTHY, DeviceState.HEALTHY, DeviceState.HEALTHY);
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails())
                .containsEntry("total", 3)
                .containsEntry("healthy", 3L)
                .containsEntry("degraded", 0L)
                .containsEntry("unreachable", 0L);
    }

    @Test
    void someDegraded_noUnreachable_isUpWithDetails() {
        whenSnapshots(DeviceState.HEALTHY, DeviceState.DEGRADED, DeviceState.DEGRADED);
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("degraded", 2L);
    }

    @Test
    void someUnreachable_butNotAll_isOutOfService() {
        whenSnapshots(DeviceState.HEALTHY, DeviceState.UNREACHABLE);
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(CollectorHealthIndicator.DEGRADED);
        assertThat(h.getDetails()).containsEntry("unreachable", 1L);
    }

    @Test
    void allUnreachable_isDown() {
        whenSnapshots(DeviceState.UNREACHABLE, DeviceState.UNREACHABLE);
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsEntry("unreachable", 2L);
    }

    private void whenSnapshots(DeviceState... states) {
        when(svc.isRunning()).thenReturn(true);
        when(svc.snapshots()).thenReturn(java.util.Arrays.stream(states)
                .map(s -> new DeviceSnapshot("d", "M", s, null, Instant.now(), 0, 0, 0, null))
                .toList());
    }
}
