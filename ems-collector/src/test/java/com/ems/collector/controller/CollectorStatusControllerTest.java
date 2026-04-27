package com.ems.collector.controller;

import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.poller.DeviceState;
import com.ems.collector.service.CollectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase I unit test — 验证 controller → service.snapshots() → DTO 的串联与 lastError 截断。
 * 权限/集成测试在 Phase L MVC layer 跑。
 */
class CollectorStatusControllerTest {

    private CollectorService svc;
    private CollectorStatusController controller;

    @BeforeEach
    void setUp() {
        svc = mock(CollectorService.class);
        controller = new CollectorStatusController(svc);
    }

    @Test
    void status_emptyList_returnsEmptyResult() {
        when(svc.snapshots()).thenReturn(List.of());
        var resp = controller.status();
        assertThat(resp.code()).isZero();
        assertThat(resp.data()).isEmpty();
    }

    @Test
    void status_mapsAllSnapshotFields() {
        DeviceSnapshot s = new DeviceSnapshot(
                "dev-A", "MOCK-M-ELEC-001", DeviceState.HEALTHY,
                Instant.parse("2026-04-27T10:00:00Z"),
                Instant.parse("2026-04-27T09:00:00Z"),
                0, 100L, 5L, null
        );
        when(svc.snapshots()).thenReturn(List.of(s));

        var resp = controller.status();
        assertThat(resp.data()).hasSize(1);
        DeviceStatusDTO dto = resp.data().get(0);
        assertThat(dto.deviceId()).isEqualTo("dev-A");
        assertThat(dto.meterCode()).isEqualTo("MOCK-M-ELEC-001");
        assertThat(dto.state()).isEqualTo(DeviceState.HEALTHY);
        assertThat(dto.lastReadAt()).isEqualTo(Instant.parse("2026-04-27T10:00:00Z"));
        assertThat(dto.successCount()).isEqualTo(100L);
        assertThat(dto.failureCount()).isEqualTo(5L);
        assertThat(dto.lastError()).isNull();
    }

    @Test
    void status_truncatesLongErrorMessage() {
        String longErr = "x".repeat(500);
        DeviceSnapshot s = new DeviceSnapshot(
                "dev", "M", DeviceState.UNREACHABLE,
                null, Instant.parse("2026-04-27T00:00:00Z"),
                10, 0L, 10L, longErr
        );
        when(svc.snapshots()).thenReturn(List.of(s));

        var resp = controller.status();
        String trimmed = resp.data().get(0).lastError();
        assertThat(trimmed).hasSize(201);    // 200 chars + ellipsis
        assertThat(trimmed).endsWith("…");
    }

    @Test
    void status_preservesYamlOrderFromSnapshots() {
        when(svc.snapshots()).thenReturn(List.of(
                snap("z-last"), snap("a-first"), snap("m-middle")
        ));
        var ids = controller.status().data().stream().map(DeviceStatusDTO::deviceId).toList();
        assertThat(ids).containsExactly("z-last", "a-first", "m-middle");
    }

    @Test
    void running_reflectsServiceState() {
        when(svc.isRunning()).thenReturn(true);
        when(svc.snapshots()).thenReturn(List.of(snap("a"), snap("b")));
        var info = controller.running().data();
        assertThat(info.running()).isTrue();
        assertThat(info.deviceCount()).isEqualTo(2);
    }

    private static DeviceSnapshot snap(String id) {
        return new DeviceSnapshot(id, id, DeviceState.HEALTHY, null, Instant.now(), 0, 0, 0, null);
    }
}
