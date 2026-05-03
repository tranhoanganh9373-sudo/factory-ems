package com.ems.collector.health;

import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.poller.DeviceState;
import com.ems.collector.service.CollectorService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Spring Boot Actuator health indicator，端点 {@code /actuator/health/collector}。
 *
 * <p>聚合规则：
 * <ul>
 *   <li>collector 未运行 → UNKNOWN（{@code disabled=true}）</li>
 *   <li>无 device → UP（"empty deployment"）</li>
 *   <li>所有 HEALTHY → UP</li>
 *   <li>有 DEGRADED 但无 UNREACHABLE → UP 但 details 标 {@code degraded=N}</li>
 *   <li>有 UNREACHABLE 且非全部 → {@link #DEGRADED}（自定 status，actuator 默认无）</li>
 *   <li>所有 device UNREACHABLE → DOWN</li>
 * </ul>
 *
 * <p>自定 DEGRADED status 用 {@link Status#OUT_OF_SERVICE}（actuator 内置含义"运维已知服务降级"），
 * 避免引入未注册的 status 名给监控报警添加噪音。
 */
@Component("collector")
public class CollectorHealthIndicator implements HealthIndicator {

    /** 部分 device UNREACHABLE 时映射成 OUT_OF_SERVICE — actuator 内置语义贴近"降级"。 */
    static final Status DEGRADED = Status.OUT_OF_SERVICE;

    private final CollectorService collector;

    public CollectorHealthIndicator(CollectorService collector) {
        this.collector = collector;
    }

    @Override
    public Health health() {
        if (!collector.isRunning()) {
            return Health.unknown().withDetail("disabled", true).build();
        }
        List<DeviceSnapshot> snaps = collector.snapshots();
        if (snaps.isEmpty()) {
            return Health.up().withDetail("devices", 0).build();
        }
        long healthy = snaps.stream().filter(s -> s.state() == DeviceState.HEALTHY).count();
        long degraded = snaps.stream().filter(s -> s.state() == DeviceState.DEGRADED).count();
        long unreachable = snaps.stream().filter(s -> s.state() == DeviceState.UNREACHABLE).count();
        Map<String, Object> details = Map.of(
                "total", snaps.size(),
                "healthy", healthy,
                "degraded", degraded,
                "unreachable", unreachable
        );

        Status status;
        if (unreachable == snaps.size()) {
            status = Status.DOWN;
        } else if (unreachable > 0) {
            status = DEGRADED;
        } else if (degraded > 0) {
            status = Status.UP;   // up but with degraded count exposed
        } else {
            status = Status.UP;
        }
        return Health.status(status).withDetails(details).build();
    }
}
