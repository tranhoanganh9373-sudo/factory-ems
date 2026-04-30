package com.ems.collector.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采集模块业务 metrics — 实现 spec §8.2 中的 5 个指标。
 *
 * <ul>
 *   <li>{@code ems.collector.poll.duration} (Timer, label {@code adapter}) — 一轮设备采集耗时</li>
 *   <li>{@code ems.collector.devices.online} (Gauge) — 当前在线设备数（HEALTHY+DEGRADED）</li>
 *   <li>{@code ems.collector.devices.offline} (Gauge) — 当前离线设备数（UNREACHABLE）</li>
 *   <li>{@code ems.collector.read.success.total} (Counter, label {@code device_id}) — 单次寄存器读成功累计</li>
 *   <li>{@code ems.collector.read.failure.total} (Counter, labels {@code device_id} + {@code reason}) — 失败累计</li>
 * </ul>
 *
 * <p>Granularity 说明：spec 文本写"单次寄存器读"，但 DevicePoller 当前 cycle 结构是"一次 cycle = 多寄存器顺序读"，
 * 失败按 cycle 级（任一 attempt 全成功才记 success；retries+1 全失败才记 failure）。本实现按 cycle-attempt 粒度
 * 记录：每次成功的 cycle → +1 success；每次失败的 cycle → +1 failure。这与 spec 期望的 cardinality 与
 * dashboard rate 计算一致（rate(read_failure_total[5m]) 仍能反映出错频率）。
 *
 * <p>{@code reason} 取值规范化为：{@code timeout / crc / format / disconnected / other}；未知值落到 {@code other}。
 *
 * <p>{@code adapter} 取值：{@code modbus-tcp} / {@code modbus-rtu}（由 caller 从 {@code Protocol} 枚举派生，
 * 见 {@link com.ems.collector.poller.DevicePoller}）。
 */
@Component
public class CollectorMetrics {

    /** 已知 reason 集合；未匹配 → 归一化为 "other"。 */
    static final Set<String> KNOWN_REASONS =
            Set.of("timeout", "crc", "format", "disconnected", "other");

    /**
     * NOOP 单例：用于手动构造 DevicePoller 的测试 / 不希望接 Spring registry 的场景。
     * 内部使用一次性 SimpleMeterRegistry，写入会累积但不会被任何 scrape 看到，
     * 等价于 NOOP。
     */
    public static final CollectorMetrics NOOP = new CollectorMetrics(new SimpleMeterRegistry());

    private final MeterRegistry registry;
    private final AtomicLong onlineHolder = new AtomicLong();
    private final AtomicLong offlineHolder = new AtomicLong();

    public CollectorMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("ems.collector.devices.online", onlineHolder);
        registry.gauge("ems.collector.devices.offline", offlineHolder);
    }

    public void recordPoll(String adapter, Duration duration) {
        Timer.builder("ems.collector.poll.duration")
                .description("一轮设备采集耗时分布")
                .tag("adapter", adapter)
                .register(registry)
                .record(duration);
    }

    public void setOnline(long n) {
        onlineHolder.set(n);
    }

    public void setOffline(long n) {
        offlineHolder.set(n);
    }

    public void recordReadSuccess(String deviceId) {
        Counter.builder("ems.collector.read.success.total")
                .description("单次寄存器读成功累计")
                .tag("device_id", deviceId)
                .register(registry)
                .increment();
    }

    public void recordReadFailure(String deviceId, String reason) {
        String normalized = KNOWN_REASONS.contains(reason) ? reason : "other";
        Counter.builder("ems.collector.read.failure.total")
                .description("单次寄存器读失败累计")
                .tag("device_id", deviceId)
                .tag("reason", normalized)
                .register(registry)
                .increment();
    }
}
