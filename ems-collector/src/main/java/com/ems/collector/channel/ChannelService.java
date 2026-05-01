package com.ems.collector.channel;

import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.runtime.ConnectionState;
import com.ems.collector.sink.SampleWriter;
import com.ems.collector.transport.ChannelTransportFactory;
import com.ems.collector.transport.Transport;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map.Entry;
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

    public Optional<Transport> activeTransport(Long id) {
        return Optional.ofNullable(active.get(id));
    }

    private void startChannel(Channel ch) {
        Transport t = factory.create(ch.getProtocol());
        stateRegistry.register(ch.getId(), ch.getProtocol());
        long startedAt = System.currentTimeMillis();
        t.start(ch.getId(), ch.getProtocolConfig(), sample -> {
            try {
                sampleWriter.write(sample);
            } catch (Exception e) {
                log.warn("sampleWriter.write failed for channel={} point={}: {}",
                        sample.channelId(), sample.pointKey(), e.getMessage());
            }
            stateRegistry.recordSuccess(sample.channelId(),
                    System.currentTimeMillis() - startedAt);
        });
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
    }

    /** Spring shutdown 钩子：停掉所有 active transport（保证测试 / 优雅停机）。 */
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
