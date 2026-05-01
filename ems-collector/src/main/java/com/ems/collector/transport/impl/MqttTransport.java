package com.ems.collector.transport.impl;

import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.transport.SampleSink;
import com.ems.collector.transport.TestResult;
import com.ems.collector.transport.Transport;

/**
 * Stub — 由 Phase 6 实现（Paho MQTT v3/v5 Client + topic 订阅 + JSONPath 提取）。
 */
public final class MqttTransport implements Transport {

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        throw new UnsupportedOperationException("MqttTransport not yet implemented in Phase 6");
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
        return TestResult.fail("MqttTransport stub — implement in Phase 6");
    }
}
