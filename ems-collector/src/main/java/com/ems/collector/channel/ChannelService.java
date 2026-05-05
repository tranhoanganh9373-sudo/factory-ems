package com.ems.collector.channel;

import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.runtime.ConnectionState;
import com.ems.collector.sink.SampleWriter;
import com.ems.collector.transport.ChannelTransportFactory;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.Transport;
import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
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

    /**
     * 不允许作为通道名的保留字——5 个 enum 值（DB chk_channel_protocol 约束的取值）+ 常用展示标签。
     * 全部以 UPPER 形式存储；比对时把入参 trim().toUpperCase(Locale.ROOT) 后查表。
     * 中文标签无大小写之分，与自身 upper-case 后等价。
     */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "MODBUS_TCP", "MODBUS_RTU", "OPC_UA", "MQTT", "VIRTUAL",
            "MODBUS TCP", "MODBUS RTU", "OPC UA",
            "虚拟", "虚拟（模拟）", "虚拟(模拟)"
    );

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
                // 不阻塞其他 channel；register/recordFailure/setState 已在 startChannel 内处理。
                log.error("failed to start channel {} ({}): {}",
                        ch.getName(), ch.getProtocol(), e.getMessage());
            }
        }
        log.info("ChannelService started: active={}", active.size());
    }

    /**
     * 创建通道：先落库，再尝试启动；启动失败不回滚也不抛 500，通道仍以 ERROR 状态存在，
     * 让用户能在 UI 上看到并修配置。语义与 {@link #startAllEnabled} 一致——
     * "通道已创建"和"通道当前不可达"是两件事。
     *
     * <p>名字校验在 save 之前——重名 / 留白 / 撞协议名直接抛 BusinessException 不入库。
     */
    public Channel create(Channel ch) {
        validateName(ch, null);
        ch.setName(ch.getName().trim());
        Channel saved = repo.save(ch);
        if (saved.isEnabled()) {
            try {
                startChannel(saved);
            } catch (Exception e) {
                log.error("channel {} ({}) created but initial start failed: {}",
                        saved.getName(), saved.getProtocol(), e.getMessage());
            }
        }
        return saved;
    }

    public Channel update(Long id, Channel updated) {
        // 名字校验先行：失败时不应该把旧 transport 停掉。
        validateName(updated, id);
        updated.setName(updated.getName().trim());
        stopChannel(id);
        updated.setId(id);
        Channel saved = repo.save(updated);
        if (saved.isEnabled()) {
            try {
                startChannel(saved);
            } catch (Exception e) {
                log.error("channel {} ({}) updated but restart failed: {}",
                        saved.getName(), saved.getProtocol(), e.getMessage());
            }
        }
        return saved;
    }

    /**
     * 通道名硬约束：非空、不撞协议名、不与现有通道重名。
     *
     * @param excludeId update 场景下传入当前通道 id，自身重名不算冲突；create 传 null。
     */
    private void validateName(Channel ch, Long excludeId) {
        String raw = ch.getName();
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "通道名称不能为空");
        }
        if (RESERVED_NAMES.contains(name.toUpperCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "通道名称不能与协议名相同：" + name);
        }
        repo.findByName(name).ifPresent(existing -> {
            if (excludeId == null || !existing.getId().equals(excludeId)) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "通道名称已存在：" + name);
            }
        });
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
        // 先 register：保证即使 factory.create 失败，也有一个可被 setState(ERROR) 命中的状态条目。
        stateRegistry.register(ch.getId(), ch.getProtocol());
        CycleAggregator agg = cycleAggs.computeIfAbsent(ch.getId(), k -> new CycleAggregator());
        Transport t;
        try {
            t = factory.create(ch.getProtocol());
            t.start(ch.getId(), ch.getProtocolConfig(), sample -> {
                try {
                    sampleWriter.write(sample);
                } catch (Exception e) {
                    log.warn("sampleWriter.write failed for channel={} point={}: {}",
                            sample.channelId(), sample.pointKey(), e.getMessage());
                }
                // 区分 cycle-ok 与 point-quality：单点解码失败（errorKind=decode）只影响该点 quality，
                // 不污染整通道的 connState/24h 成功率——TCP/串口仍然连通，只是某个点的 dataType 配错。
                // 传输层 IO 失败（errorKind=io，或未打 tag 的兜底）才计入 cycle failure。
                boolean isDecodeOnly = sample.quality() != Quality.GOOD
                        && Sample.ERROR_KIND_DECODE.equals(sample.tags().get(Sample.TAG_ERROR_KIND));
                boolean cycleOk = sample.quality() == Quality.GOOD || isDecodeOnly;
                String err = cycleOk ? null
                        : sample.tags().getOrDefault("error", "quality=" + sample.quality());
                commitCycle(sample.channelId(), agg, cycleOk, err);
            });
        } catch (RuntimeException e) {
            stateRegistry.recordFailure(ch.getId(), e.getMessage());
            // setState 必须在 recordFailure 之后——recordFailure 会把 connState 改写成 DISCONNECTED。
            stateRegistry.setState(ch.getId(), ConnectionState.ERROR);
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
