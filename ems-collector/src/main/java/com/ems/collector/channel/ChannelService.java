package com.ems.collector.channel;

import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.runtime.ConnectionState;
import com.ems.collector.sink.SampleWriter;
import com.ems.collector.transport.ChannelTransportFactory;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Transport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Channel 生命周期编排：DB → Transport instance → SampleWriter。
 *
 * <p>启动顺序（{@link #startAllEnabled}）：
 * <ol>
 *   <li>从 channel 表加载所有 enabled=true</li>
 *   <li>每个 channel 实例化 Transport → register 状态 → start()</li>
 *   <li>启动失败的 channel 单独标 ERROR 状态，不阻塞其他 channel</li>
 * </ol>
 *
 * <p>运行时增删改通过 {@link #create}/{@link #update}/{@link #delete}（由 ChannelController 暴露）。
 */
@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private final ChannelRepository repo;
    private final ChannelStateRegistry stateRegistry;
    private final ChannelTransportFactory factory;
    private final SampleWriter sampleWriter;
    private final ConcurrentHashMap<Long, Transport> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CycleAggregator> cycleAggs = new ConcurrentHashMap<>();

    /**
     * Per-channel 轮询周期聚合器：把一次轮询里多个点位的 sample 折叠成一次 success/failure，
     * 让 24h 成功率反映"通讯周期级"健康度而不是"逐点"成功率。
     *
     * <p>周期边界判定：相邻 sample 间隔超过 {@link #CYCLE_GAP_NANOS} 即视为新周期开始，
     * 此时把上一周期累计结果 commit 到 stateRegistry。
     *
     * <p>{@code cycleStartNanos} 记录当前周期第一条 sample 的到达时刻；commit 时
     * 用 {@code lastSampleNanos - cycleStartNanos} 表示该周期的实际采集耗时，
     * 即"上报给 UI 的平均延迟"口径。
     */
    private static final class CycleAggregator {
        long cycleStartNanos = 0;
        long lastSampleNanos = 0;
        int successCount = 0;
        int failureCount = 0;
        String lastError = null;
    }

    /** 200ms：modbus 多点 read 一般 &lt;100ms 完成，留 2× 余量。 */
    private static final long CYCLE_GAP_NANOS = 200_000_000L;

    public ChannelService(ChannelRepository repo,
                          ChannelStateRegistry stateRegistry,
                          ChannelTransportFactory factory,
                          SampleWriter sampleWriter) {
        this.repo = repo;
        this.stateRegistry = stateRegistry;
        this.factory = factory;
        this.sampleWriter = sampleWriter;
    }

    @PostConstruct
    public void startAllEnabled() {
        for (Channel ch : repo.findByEnabledTrue()) {
            try {
                startChannel(ch);
            } catch (Exception e) {
                log.error("failed to start channel {} ({}): {}",
                        ch.getName(), ch.getProtocol(), e.getMessage());
                // 标 ERROR 但不重抛 — 不影响其他 channel
                // 注意：setState 必须在 recordFailure 之后，
                // 否则 recordFailure 会把 ERROR 覆盖回 DISCONNECTED
                stateRegistry.register(ch.getId(), ch.getProtocol());
                stateRegistry.recordFailure(ch.getId(), e.getMessage());
                stateRegistry.setState(ch.getId(), ConnectionState.ERROR);
            }
        }
        log.info("ChannelService started: active={}", active.size());
    }

    public Channel create(Channel ch) {
        Channel saved = repo.save(ch);
        if (saved.isEnabled()) {
            startChannel(saved);
        }
        return saved;
    }

    public Channel update(Long id, Channel updated) {
        stopChannel(id);
        updated.setId(id);
        Channel saved = repo.save(updated);
        if (saved.isEnabled()) {
            startChannel(saved);
        }
        return saved;
    }

    public void delete(Long id) {
        stopChannel(id);
        repo.deleteById(id);
        stateRegistry.unregister(id);
    }

    /** 强制 stop + 重新 start 已存在的 channel；不修改 DB（updated_at 不变）。 */
    public void restart(Long id) {
        Channel ch = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("channel not found: " + id));
        stopChannel(id);
        if (ch.isEnabled()) {
            startChannel(ch);
        }
    }

    public Optional<Transport> activeTransport(Long id) {
        return Optional.ofNullable(active.get(id));
    }

    private void startChannel(Channel ch) {
        Transport t = factory.create(ch.getProtocol());
        stateRegistry.register(ch.getId(), ch.getProtocol());
        CycleAggregator agg = cycleAggs.computeIfAbsent(ch.getId(), k -> new CycleAggregator());
        try {
            t.start(ch.getId(), ch.getProtocolConfig(), sample -> {
                try {
                    sampleWriter.write(sample);
                } catch (Exception e) {
                    log.warn("sampleWriter.write failed for channel={} point={}: {}",
                            sample.channelId(), sample.pointKey(), e.getMessage());
                }
                String err = sample.quality() == Quality.GOOD ? null
                        : sample.tags().getOrDefault("error", "quality=" + sample.quality());
                commitCycle(sample.channelId(), agg, sample.quality() == Quality.GOOD, err);
            });
        } catch (RuntimeException e) {
            stateRegistry.recordFailure(ch.getId(), e.getMessage());
            throw e;
        }
        active.put(ch.getId(), t);
        log.info("channel {} ({}) started", ch.getName(), ch.getProtocol());
    }

    private void stopChannel(Long id) {
        Transport t = active.remove(id);
        if (t != null) {
            try {
                t.stop();
            } catch (Exception e) {
                log.warn("stop channel {} error: {}", id, e.getMessage());
            }
        }
        cycleAggs.remove(id);
    }

    /**
     * 把一次 sample 累入 per-channel 周期聚合器；当检测到新周期开始时，把上一周期累计结果
     * commit 到 stateRegistry —— 整周期任一点失败 → 整周期算 failure，否则算 success。
     *
     * <p>延迟口径：周期最后一条 sample 与第一条 sample 的时间差，即"通讯一轮的实际耗时"。
     * 单点周期（虚拟通道、单寄存器 modbus）天然为 0；多点 modbus 反映总轮询时长。
     */
    private void commitCycle(Long channelId, CycleAggregator agg, boolean ok, String err) {
        synchronized (agg) {
            long now = System.nanoTime();
            long gap = now - agg.lastSampleNanos;
            boolean hasPrior = agg.successCount > 0 || agg.failureCount > 0;
            if (hasPrior && gap > CYCLE_GAP_NANOS) {
                if (agg.failureCount == 0) {
                    long cycleDurationMs = (agg.lastSampleNanos - agg.cycleStartNanos) / 1_000_000L;
                    stateRegistry.recordSuccess(channelId, cycleDurationMs);
                } else {
                    stateRegistry.recordFailure(channelId,
                            agg.lastError != null ? agg.lastError : "cycle had failures");
                }
                agg.successCount = 0;
                agg.failureCount = 0;
                agg.lastError = null;
                agg.cycleStartNanos = now;
            } else if (!hasPrior) {
                agg.cycleStartNanos = now;
            }
            if (ok) {
                agg.successCount++;
            } else {
                agg.failureCount++;
                if (err != null) agg.lastError = err;
            }
            agg.lastSampleNanos = now;
        }
    }

    /** Spring shutdown 钩子：停掉所有 active transport（保证测试 / 优雅停机）。 */
    @PreDestroy
    public void stopAll() {
        for (Entry<Long, Transport> e : active.entrySet()) {
            try {
                e.getValue().stop();
            } catch (Exception ex) {
                log.warn("stop channel {} during shutdown error: {}", e.getKey(), ex.getMessage());
            }
        }
        active.clear();
    }
}
