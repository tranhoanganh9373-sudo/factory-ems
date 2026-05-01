package com.ems.collector.transport.impl;

import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.transport.SampleSink;
import com.ems.collector.transport.TestResult;
import com.ems.collector.transport.Transport;

/**
 * Stub — 由 Phase 5 实现（Eclipse Milo OPC UA Client + Subscription / Read 模式）。
 */
public final class OpcUaTransport implements Transport {

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        throw new UnsupportedOperationException("OpcUaTransport not yet implemented in Phase 5");
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
        return TestResult.fail("OpcUaTransport stub — implement in Phase 5");
    }
}
