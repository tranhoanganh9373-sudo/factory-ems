package com.ems.collector.transport.impl;

import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.transport.SampleSink;
import com.ems.collector.transport.TestResult;
import com.ems.collector.transport.Transport;

/**
 * Stub — 由 Phase 4 实现完整 VIRTUAL 协议（信号生成器 + 调度）。
 *
 * <p>Phase 2 阶段仅提供占位以满足 sealed permits 列表。
 */
public final class VirtualTransport implements Transport {

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        throw new UnsupportedOperationException("VirtualTransport not yet implemented in Phase 4");
    }

    @Override
    public void stop() {
        // no-op
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public TestResult testConnection(ChannelConfig config) {
        return TestResult.fail("VirtualTransport stub — implement in Phase 4");
    }
}
