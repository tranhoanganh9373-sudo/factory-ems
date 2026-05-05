package com.ems.collector.diagnostics;

import com.ems.collector.channel.Channel;
import com.ems.collector.channel.ChannelRepository;
import com.ems.collector.channel.ChannelService;
import com.ems.collector.runtime.ChannelRuntimeState;
import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.sink.DiagnosticRingBuffer;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.TestResult;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Channel 诊断服务：暴露运行时状态、连接测试、强制重连。
 *
 * <p>状态来源是 {@link ChannelStateRegistry}（内存快照），不再查 DB；
 * 测试 / 重连则委托给 {@link ChannelService}。
 */
@Service
public class ChannelDiagnosticsService {

    private static final int DEFAULT_SAMPLE_LIMIT = 20;
    private static final int MAX_SAMPLE_LIMIT = 100;

    private final ChannelStateRegistry registry;
    private final ChannelService channelService;
    private final ChannelRepository repo;
    private final DiagnosticRingBuffer ring;

    public ChannelDiagnosticsService(ChannelStateRegistry registry,
                                     ChannelService channelService,
                                     ChannelRepository repo,
                                     DiagnosticRingBuffer ring) {
        this.registry = registry;
        this.channelService = channelService;
        this.repo = repo;
        this.ring = ring;
    }

    public Collection<ChannelRuntimeState> snapshotAll() {
        return registry.snapshotAll();
    }

    public ChannelRuntimeState snapshot(Long id) {
        return registry.snapshot(id);
    }

    public TestResult test(Long id) {
        Channel ch = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("channel not found: " + id));
        return channelService.activeTransport(id)
                .map(t -> t.testConnection(ch.getProtocolConfig()))
                .orElseGet(() -> TestResult.fail("channel not active"));
    }

    public void reconnect(Long id) {
        // 不调用 channelService.update — update() 会重写 channel + 触发 updatedAt 变化。
        // restart 只 stop+start transport，DB 不动。
        channelService.restart(id);
    }

    /**
     * 返回 channel 最近样本（最新在前）。诊断 ring buffer 由 {@link DiagnosticRingBuffer}
     * 维护，InfluxSampleWriter / LoggingSampleWriter 都喂它，所以不论时序库是否在线
     * 都能看到样本——曾经的"生产环境无 ring buffer"限制已移除。
     *
     * <p>limit 兜底：&lt;=0 → {@value #DEFAULT_SAMPLE_LIMIT}；&gt;{@value #MAX_SAMPLE_LIMIT} → 截断。
     */
    public List<SampleDTO> getRecentSamples(Long channelId, int limit) {
        int effectiveLimit = limit <= 0 ? DEFAULT_SAMPLE_LIMIT
            : Math.min(limit, MAX_SAMPLE_LIMIT);
        return ring.getRecentSamples(channelId, effectiveLimit).stream()
            .map(ChannelDiagnosticsService::toDto)
            .toList();
    }

    private static SampleDTO toDto(Sample s) {
        return new SampleDTO(
            s.pointKey(),
            s.timestamp() != null ? s.timestamp().toString() : null,
            s.value(),
            s.quality() != null ? s.quality().name() : null,
            s.tags()
        );
    }
}
