package com.ems.collector.service;

import com.ems.collector.config.CollectorProperties;
import com.ems.collector.config.DeviceConfig;
import com.ems.collector.poller.DevicePoller;
import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.poller.ReadingSink;
import com.ems.collector.transport.ModbusMaster;
import com.ems.collector.transport.ModbusMasterFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CollectorService.class);

    private final CollectorProperties props;
    private final ReadingSink sink;
    private final ModbusMasterFactory masterFactory;
    private final Clock clock;
    private final DevicePoller.StateTransitionListener stateListener;

    private final Map<String, DevicePoller> pollers = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public CollectorService(CollectorProperties props,
                            ReadingSink sink,
                            ModbusMasterFactory masterFactory,
                            Clock clock,
                            DevicePoller.StateTransitionListener stateListener) {
        this.props = props;
        this.sink = sink;
        this.masterFactory = masterFactory;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.stateListener = stateListener == null ? DevicePoller.StateTransitionListener.NOOP : stateListener;
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
            DevicePoller poller = new DevicePoller(dev, master, sink, clock, stateListener);
            pollers.put(dev.id(), poller);
        }

        running = true;
        // Stagger initial polls slightly to avoid all devices firing in lockstep on startup
        long stagger = 0;
        for (DevicePoller p : pollers.values()) {
            scheduleAfter(p, stagger);
            stagger += 100;
        }
        log.info("collector started: {} device(s)", pollers.size());
    }

    private void scheduleAfter(DevicePoller poller, long delayMs) {
        if (!running || scheduler == null || scheduler.isShutdown()) return;
        scheduler.schedule(() -> {
            try {
                poller.pollOnce();
            } catch (Throwable t) {
                log.error("device {} poller raised unexpected: {}", poller.config().id(), t.toString(), t);
            } finally {
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
