package com.ems.collector.diagnostics;

import com.ems.collector.runtime.ChannelRuntimeState;
import com.ems.collector.transport.TestResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * 诊断 REST 接口：状态查询 + 连接测试 + 强制重连。
 * 路径前缀 {@code /api/v1/collector}，仅 ADMIN / OPERATOR 可访问。
 */
@RestController
@RequestMapping("/api/v1/collector")
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class ChannelDiagnosticsController {

    private final ChannelDiagnosticsService svc;

    public ChannelDiagnosticsController(ChannelDiagnosticsService svc) {
        this.svc = svc;
    }

    @GetMapping("/state")
    public Collection<ChannelRuntimeState> all() {
        return svc.snapshotAll();
    }

    @GetMapping("/{id}/state")
    public ChannelRuntimeState one(@PathVariable Long id) {
        return svc.snapshot(id);
    }

    @PostMapping("/{id}/test")
    public TestResult test(@PathVariable Long id) {
        return svc.test(id);
    }

    @PostMapping("/{id}/reconnect")
    public void reconnect(@PathVariable Long id) {
        svc.reconnect(id);
    }
}
