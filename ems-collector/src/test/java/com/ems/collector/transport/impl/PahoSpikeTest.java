package com.ems.collector.transport.impl;

import com.jayway.jsonpath.JsonPath;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PahoSpikeTest {
    @Test
    void canConstructPahoOptions() {
        // 验证 Paho jar 在 classpath + import 路径正确
        var opts = new MqttConnectOptions();
        opts.setKeepAliveInterval(60);
        assertThat(opts.getKeepAliveInterval()).isEqualTo(60);
    }

    @Test
    void canExtractWithJsonPath() {
        // 验证 JsonPath 解析能力（Task 6.2 的 MqttTransport 依赖此能力）
        var json = "{\"value\":23.5,\"unit\":\"C\"}";
        Double v = JsonPath.read(json, "$.value");
        assertThat(v).isEqualTo(23.5);
    }
}
