package com.ems.collector.diagnostics;

import com.ems.collector.channel.Channel;
import com.ems.collector.channel.ChannelRepository;
import com.ems.collector.channel.ChannelService;
import com.ems.collector.runtime.ChannelRuntimeState;
import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.transport.TestResult;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.NoSuchElementException;

/**
 * Channel 诊断服务：暴露运行时状态、连接测试、强制重连。
 *
 * <p>状态来源是 {@link ChannelStateRegistry}（内存快照），不再查 DB；
 * 测试 / 重连则委托给 {@link ChannelService}。
 */
@Service
public class ChannelDiagnosticsService {

    private final ChannelStateRegistry registry;
    private final ChannelService channelService;
    private final ChannelRepository repo;

    public ChannelDiagnosticsService(ChannelStateRegistry registry,
                                     ChannelService channelService,
                                     ChannelRepository repo) {
        this.registry = registry;
        this.channelService = channelService;
        this.repo = repo;
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
}
