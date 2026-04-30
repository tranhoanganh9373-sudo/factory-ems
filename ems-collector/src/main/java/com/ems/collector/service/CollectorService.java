package com.ems.collector.service;

import com.ems.collector.config.CollectorProperties;
import com.ems.collector.config.DeviceConfig;
import com.ems.collector.config.Protocol;
import com.ems.collector.health.CollectorMetrics;
import com.ems.collector.poller.DevicePoller;
import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.poller.DeviceState;
import com.ems.collector.poller.ReadingSink;
import com.ems.collector.transport.ModbusMaster;
import com.ems.collector.transport.ModbusMasterFactory;
import com.ems.collector.transport.SerialPortLockRegistry;
import com.ems.meter.observability.MeterMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 YAML 里的 device 列表转成运行中的 {@link DevicePoller}，调度它们 + 优雅关闭。
 *
 * <p>调度策略：每个 device 一个独立递归任务。每次 {@code pollOnce()} 完成后，根据
 * poller 当前状态查 {@link DevicePoller#nextDelayMs()} 决定下次延迟，再次 schedule。
 * 这样 HEALTHY → DEGRADED → UNREACHABLE 之间切换的延迟变化能立即生效，无需重 schedule。
 *
 * <p>线程池：core size = max(1, devices.size())。一台 PC 上 ~50 device 也就 50 个线程，
 * 符合 ScheduledExecutor 调度特性。device 数量过多（>200）需要换 work-stealing pool。
 *
 * <p>优雅关闭：{@link #shutdown()} 在 {@link PreDestroy} 时调用，先停接受新任务，等
 * {@link #SHUTDOWN_TIMEOUT_SECONDS}s 让 in-flight read 完成，超时强 cancel + close 所有 master。
 *
 * <p>{@code ems.collector.enabled=false} 时 {@link #start()} 直接返回，pollers 列表空。
 */
@Service
public class CollectorService {

    /** 优雅关闭时给 in-flight read 留多少秒。 */
    static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    /** Spec §8.2 设备在/离线 gauge 周期刷新间隔。 */
    static final long DEVICE_STATE_REFRESH_MS = 30_000L;

    private static final Logger log = LoggerFactory.getLogger(CollectorService.class);

    private final CollectorProperties props;
    private final ReadingSink sink;
    private final ModbusMasterFactory masterFactory;
    private final Clock clock;
    private final DevicePoller.StateTransitionListener stateListener;
    private final CollectorMetrics metrics;
    private final com.ems.collector.observability.CollectorMetrics businessMetrics;
    private final MeterMetrics meterMetrics;
    private final SerialPortLockRegistry serialLocks;

    private final Map<String, DevicePoller> pollers = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    /** follow-up #5: gauge 刷新独立线程池，避免与 device polling 抢 worker slot（>200 设备时尤甚）。 */
    private ScheduledExecutorService gaugeScheduler;
    private volatile boolean running = false;

    public CollectorService(CollectorProperties props,
                            ReadingSink sink,
                            ModbusMasterFactory masterFactory,
                            Clock clock,
                            DevicePoller.StateTransitionListener stateListener,
                            CollectorMetrics metrics,
                            SerialPortLockRegistry serialLocks) {
        this(props, sink, masterFactory, clock, stateListener, metrics, serialLocks,
                com.ems.collector.observability.CollectorMetrics.NOOP, MeterMetrics.NOOP);
    }

    public CollectorService(CollectorProperties props,
                            ReadingSink sink,
                            ModbusMasterFactory masterFactory,
                            Clock clock,
                            DevicePoller.StateTransitionListener stateListener,
                            CollectorMetrics metrics,
                            SerialPortLockRegistry serialLocks,
                            com.ems.collector.observability.CollectorMetrics businessMetrics) {
        this(props, sink, masterFactory, clock, stateListener, metrics, serialLocks,
                businessMetrics, MeterMetrics.NOOP);
    }

    public CollectorService(CollectorProperties props,
                            ReadingSink sink,
                            ModbusMasterFactory masterFactory,
                            Clock clock,
                            DevicePoller.StateTransitionListener stateListener,
                            CollectorMetrics metrics,
                            SerialPortLockRegistry serialLocks,
                            com.ems.collector.observability.CollectorMetrics businessMetrics,
                            MeterMetrics meterMetrics) {
        this.props = props;
        this.sink = sink;
        this.masterFactory = masterFactory;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.stateListener = stateListener == null ? DevicePoller.StateTransitionListener.NOOP : stateListener;
        this.metrics = metrics;
        this.businessMetrics = businessMetrics == null
                ? com.ems.collector.observability.CollectorMetrics.NOOP : businessMetrics;
        this.meterMetrics = meterMetrics == null ? MeterMetrics.NOOP : meterMetrics;
        this.serialLocks = serialLocks == null ? new SerialPortLockRegistry() : serialLocks;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (running) {
            log.debug("collector already running, ignoring start()");
            return;
        }
        if (!props.enabled()) {
            log.info("collector disabled (ems.collector.enabled=false)");
            return;
        }
        if (props.devices() == null || props.devices().isEmpty()) {
            log.info("collector enabled but no devices configured");
            running = true;
            return;
        }

        List<DeviceConfig> devs = props.devices();
        scheduler = Executors.newScheduledThreadPool(
                Math.max(1, devs.size()), new NamedThreadFactory("ems-collector-"));

        for (DeviceConfig dev : devs) {
            ModbusMaster master = masterFactory.create(dev);
            // RTU 设备共串口必须串行；TCP device = null
            java.util.concurrent.locks.Lock lock = (dev.protocol() == Protocol.RTU)
                    ? serialLocks.lockFor(dev.serialPort())
                    : null;
            DevicePoller poller = new DevicePoller(
                    dev, master, sink, clock, stateListener, lock, businessMetrics);
            pollers.put(dev.id(), poller);
        }

        running = true;
        // Stagger initial polls slightly to avoid all devices firing in lockstep on startup
        long stagger = 0;
        for (DevicePoller p : pollers.values()) {
            scheduleAfter(p, stagger);
            stagger += 100;
        }
        // Spec §8.2: ems.collector.devices.online/offline gauge 周期刷新（30s）
        // follow-up #5: 独立 single-thread scheduler；不挤占 device polling worker slot
        gaugeScheduler = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("ems-collector-gauge-"));
        gaugeScheduler.scheduleAtFixedRate(this::refreshDeviceGauges,
                0, DEVICE_STATE_REFRESH_MS, TimeUnit.MILLISECONDS);
        log.info("collector started: {} device(s)", pollers.size());
    }

    /**
     * 计数 HEALTHY+DEGRADED → online，UNREACHABLE → offline；写入 collector gauge。
     * <p>同时刷新 meter reading lag gauge（spec §8.4）：跨所有 poller 取
     * {@code max(now - snapshot.lastReadAt)}。从未读过的 poller 跳过；空集合 → 0。
     */
    private void refreshDeviceGauges() {
        try {
            long online = 0;
            long offline = 0;
            for (DevicePoller p : pollers.values()) {
                DeviceState s = p.state();
                if (s == DeviceState.UNREACHABLE) {
                    offline++;
                } else {
                    online++;
                }
            }
            businessMetrics.setOnline(online);
            businessMetrics.setOffline(offline);

            long now = clock.instant().getEpochSecond();
            long maxLag = pollers.values().stream()
                    .map(DevicePoller::snapshot)
                    .filter(s -> s.lastReadAt() != null)
                    .mapToLong(s -> Math.max(0L, now - s.lastReadAt().getEpochSecond()))
                    .max()
                    .orElse(0L);
            meterMetrics.setMaxLagSeconds(maxLag);
        } catch (Throwable t) {
            // 不能影响 polling
            log.warn("device gauge refresh failed: {}", t.toString());
        }
    }

    private void scheduleAfter(DevicePoller poller, long delayMs) {
        if (!running || scheduler == null || scheduler.isShutdown()) return;
        scheduler.schedule(() -> {
            long startNs = System.nanoTime();
            boolean ok = false;
            try {
                ok = poller.pollOnce();
            } catch (Throwable t) {
                log.error("device {} poller raised unexpected: {}", poller.config().id(), t.toString(), t);
            } finally {
                if (metrics != null) {
                    metrics.record(poller.config().id(), ok, System.nanoTime() - startNs);
                }
                if (running) {
                    scheduleAfter(poller, poller.nextDelayMs());
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (!running) return;
        running = false;
        log.info("collector stopping: {} pollers", pollers.size());
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("collector scheduler did not terminate in {}s; forcing", SHUTDOWN_TIMEOUT_SECONDS);
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
        // follow-up #5: gauge scheduler 与 polling scheduler 同步 shutdown
        if (gaugeScheduler != null) {
            gaugeScheduler.shutdown();
            try {
                if (!gaugeScheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("collector gauge scheduler did not terminate in {}s; forcing", SHUTDOWN_TIMEOUT_SECONDS);
                    gaugeScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                gaugeScheduler.shutdownNow();
            }
        }
        for (DevicePoller p : pollers.values()) {
            try {
                p.shutdown();
            } catch (Exception e) {
                log.warn("error shutting down poller {}: {}", p.config().id(), e.toString());
            }
        }
        log.info("collector stopped");
    }

    /** Read-only snapshot of all device states. Order matches YAML insertion. */
    public synchronized List<DeviceSnapshot> snapshots() {
        // Preserve YAML order; pollers map is hash-based.
        Map<String, DevicePoller> ordered = new LinkedHashMap<>();
        if (props.devices() != null) {
            for (DeviceConfig d : props.devices()) {
                DevicePoller p = pollers.get(d.id());
                if (p != null) ordered.put(d.id(), p);
            }
        }
        List<DeviceSnapshot> out = new ArrayList<>(ordered.size());
        for (DevicePoller p : ordered.values()) out.add(p.snapshot());
        return out;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /** Visible for testing. */
    public synchronized Collection<DevicePoller> pollers() {
        return List.copyOf(pollers.values());
    }

    /**
     * 配置热加载：diff 新旧 device 列表 + 应用 add / remove / modify 三类变更。
     * unchanged device 的 polling 不受影响（不重 schedule）。
     *
     * <p>调用前应已经做过 JSR-303 + CollectorPropertiesValidator + meter-code 校验
     * （由 controller 层负责）。
     */
    public synchronized ReloadResult reload(CollectorProperties newProps) {
        if (!running) {
            throw new IllegalStateException("collector not running; cannot reload");
        }
        if (!newProps.enabled()) {
            throw new IllegalStateException(
                    "new config has enabled=false; reload cannot disable a running collector — restart instead");
        }
        Map<String, DeviceConfig> newDevs = new LinkedHashMap<>();
        for (DeviceConfig d : newProps.devices()) newDevs.put(d.id(), d);
        Map<String, DevicePoller> oldPollers = new LinkedHashMap<>(pollers);

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        int unchanged = 0;

        for (Map.Entry<String, DevicePoller> e : oldPollers.entrySet()) {
            String id = e.getKey();
            DeviceConfig newDev = newDevs.get(id);
            if (newDev == null) {
                e.getValue().shutdown();
                pollers.remove(id);
                removed.add(id);
            } else if (!newDev.equals(e.getValue().config())) {
                e.getValue().shutdown();
                pollers.remove(id);
                installPoller(newDev);
                modified.add(id);
            } else {
                unchanged++;
            }
        }
        for (DeviceConfig d : newProps.devices()) {
            if (!oldPollers.containsKey(d.id())) {
                installPoller(d);
                added.add(d.id());
            }
        }
        long stagger = 0;
        for (String id : added) scheduleAfter(pollers.get(id), stagger += 100);
        for (String id : modified) scheduleAfter(pollers.get(id), stagger += 100);

        log.info("collector reloaded: +{} ~{} -{} ={}",
                added.size(), modified.size(), removed.size(), unchanged);
        return new ReloadResult(added, removed, modified, unchanged);
    }

    private void installPoller(DeviceConfig dev) {
        ModbusMaster master = masterFactory.create(dev);
        java.util.concurrent.locks.Lock lock = (dev.protocol() == Protocol.RTU)
                ? serialLocks.lockFor(dev.serialPort())
                : null;
        DevicePoller poller = new DevicePoller(
                dev, master, sink, clock, stateListener, lock, businessMetrics);
        pollers.put(dev.id(), poller);
    }

    public record ReloadResult(List<String> added, List<String> removed,
                               List<String> modified, int unchanged) {}

    /** Named threads make jstack/Micrometer tags actionable. */
    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(0);

        NamedThreadFactory(String prefix) { this.prefix = prefix; }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /** Counter (Phase J 时 Micrometer 实接入；MVP 暂存内存)。 */
    @SuppressWarnings("unused")
    private static final AtomicLong UNUSED_PROBE = new AtomicLong();
}
