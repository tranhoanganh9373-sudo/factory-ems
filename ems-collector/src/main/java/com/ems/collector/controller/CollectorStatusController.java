package com.ems.collector.controller;

import com.ems.collector.service.CollectorService;
import com.ems.core.dto.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only 状态接口，给运维 UI 看每个 device 的 polling 状态。
 *
 * <p>权限：ADMIN — 包含 host / IP / error 详情，按 spec §"权限"和 /admin/audit 同级敏感度。
 *
 * <p>查询时从 {@link CollectorService#snapshots()} 拿最新内存状态（YAML 顺序保留）。
 * 不查询数据库 / 不联仪表，毫秒级返回。
 */
@RestController
@RequestMapping("/api/v1/collector")
public class CollectorStatusController {

    private final CollectorService collector;

    public CollectorStatusController(CollectorService collector) {
        this.collector = collector;
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<DeviceStatusDTO>> status() {
        List<DeviceStatusDTO> dtos = collector.snapshots().stream()
                .map(DeviceStatusDTO::from)
                .toList();
        return Result.ok(dtos);
    }

    @GetMapping("/running")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<RunningInfo> running() {
        return Result.ok(new RunningInfo(collector.isRunning(), collector.snapshots().size()));
    }

    public record RunningInfo(boolean running, int deviceCount) {}
}
