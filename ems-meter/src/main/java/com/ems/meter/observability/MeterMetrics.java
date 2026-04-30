package com.ems.meter.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 计量模块业务 metrics（spec §8.4）。
 *
 * <p>3 个指标：
 * <ul>
 *   <li>{@code ems.meter.reading.lag.seconds} — Gauge，最新读数与当前时间差（按设备聚合最大值）。
 *       由 collector 端 30s 周期任务（{@code CollectorService.refreshDeviceGauges}）按
 *       {@code now - max(snapshot.lastReadAt)} 计算后写入。</li>
 *   <li>{@code ems.meter.reading.insert.rate{energy_type}} — Counter，累计入库读数行数。
 *       由 {@code InfluxReadingSink.accept} / {@code flushOne} 在 Influx 写成功后递增。</li>
 *   <li>{@code ems.meter.reading.dropped.total{reason}} — Counter，因校验失败被丢弃的读数。
 *       v1 仅在 {@code meter == null} 路径下记 {@code reason=other}（duplicate / out_of_range /
 *       format_error 留给后续 reading 校验链路使用）。Influx 写失败 → buffer 重投，不计 dropped。</li>
 * </ul>
 *
 * <p>未知 {@code energy_type} / {@code reason} 一律归一化为 {@code other}，控制 cardinality。
 *
 * <p>实现选择 Approach A：caller（{@code InfluxReadingSink}）负责把 {@code Meter.energyTypeId}
 * 解析成 EnergyType.code 字符串再传入。MeterMetrics 不持有 EnergyTypeRepository，保持单一职责。
 */
@Component
public class MeterMetrics {

    /** 已知 energy_type；未匹配 → 归一化为 {@code "other"}。 */
    static final Set<String> KNOWN_ENERGY_TYPES =
            Set.of("elec", "water", "gas", "steam", "other");

    /** 已知 dropped reason；未匹配 → 归一化为 {@code "other"}。 */
    static final Set<String> KNOWN_DROP_REASONS =
            Set.of("duplicate", "out_of_range", "format_error", "other");

    /**
     * NOOP fallback：用于手动构造测试或不希望接 Spring registry 的场景。
     * 内部使用一次性 SimpleMeterRegistry，写入会累积但不会被任何 scrape 看到，等价于 NOOP。
     */
    public static final MeterMetrics NOOP = new MeterMetrics(new SimpleMeterRegistry());

    private final MeterRegistry registry;
    private final AtomicLong lagHolder = new AtomicLong();

    public MeterMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("ems.meter.reading.lag.seconds", lagHolder);
    }

    /**
     * 设置当前最大 reading 滞后秒数。Caller 负责跨设备聚合（取 {@code max(now - lastReadAt)}）。
     * <p>last-write-wins 语义：每次调用直接覆盖 holder，不做累加。负值钳到 0。
     */
    public void setMaxLagSeconds(long seconds) {
        lagHolder.set(Math.max(0L, seconds));
    }

    /** 累计入库读数 +1。Caller 传 EnergyType.code（{@code elec/water/gas/steam}）。 */
    public void incrementInsert(String energyTypeCode) {
        String norm = KNOWN_ENERGY_TYPES.contains(energyTypeCode) ? energyTypeCode : "other";
        Counter.builder("ems.meter.reading.insert.rate")
                .description("Total readings inserted into Influx")
                .tag("energy_type", norm)
                .register(registry)
                .increment();
    }

    /** 累计丢弃读数 +1。{@code reason} 限 {@code duplicate/out_of_range/format_error/other}。 */
    public void incrementDropped(String reason) {
        String norm = KNOWN_DROP_REASONS.contains(reason) ? reason : "other";
        Counter.builder("ems.meter.reading.dropped.total")
                .description("Total readings dropped due to validation failure")
                .tag("reason", norm)
                .register(registry)
                .increment();
    }
}
