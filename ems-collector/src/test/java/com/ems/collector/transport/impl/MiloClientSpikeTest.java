package com.ems.collector.transport.impl;

import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MiloClientSpikeTest {
    @Test
    void canCreateClientConfig() {
        // Spike 仅验证 Milo 0.6.13 jar 在 classpath 上 + import 路径正确。
        // 不调用 .build()：Milo 0.6.13 builder 在 build() 内强制要求 endpoint 非空，
        // 真实 endpoint 构造由 Task 5.3 OpcUaTransport 实现负责。
        var builder = new OpcUaClientConfigBuilder();
        assertThat(builder).isNotNull();
    }
}
