package com.ems.collector.transport.impl;

import com.ems.collector.protocol.MqttConfig;
import com.ems.collector.protocol.MqttPoint;
import com.ems.collector.protocol.VirtualConfig;
import com.ems.collector.protocol.VirtualMode;
import com.ems.collector.protocol.VirtualPoint;
import com.ems.collector.transport.TransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MqttTransport (无 broker)")
class MqttTransportTest {

    @Test
    @DisplayName("topicMatches 单段精确匹配")
    void topicMatches_exact() {
        assertThat(MqttTransport.topicMatches("a/b", "a/b")).isTrue();
        assertThat(MqttTransport.topicMatches("a/b", "a/c")).isFalse();
        assertThat(MqttTransport.topicMatches("a/b", "a/b/c")).isFalse();
    }

    @Test
    @DisplayName("topicMatches + 通配单层")
    void topicMatches_plus() {
        assertThat(MqttTransport.topicMatches("sensors/+/temp", "sensors/factory1/temp")).isTrue();
        assertThat(MqttTransport.topicMatches("sensors/+/temp", "sensors/factory1/humid")).isFalse();
        assertThat(MqttTransport.topicMatches("sensors/+/temp", "sensors/temp")).isFalse();
    }

    @Test
    @DisplayName("topicMatches # 通配多层后续")
    void topicMatches_hash() {
        assertThat(MqttTransport.topicMatches("a/#", "a/b/c/d")).isTrue();
        assertThat(MqttTransport.topicMatches("a/#", "a")).isFalse();
        assertThat(MqttTransport.topicMatches("#", "anything/at/all")).isTrue();
    }

    @Test
    @DisplayName("start 拒绝非 MqttConfig 抛 TransportException")
    void start_wrongConfig_throws() {
        var transport = new MqttTransport();
        var wrong = new VirtualConfig(Duration.ofSeconds(1),
            List.of(new VirtualPoint("p", VirtualMode.CONSTANT, Map.of("value", 1.0), null)));
        assertThatThrownBy(() -> transport.start(1L, wrong, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("MqttConfig");
    }

    @Test
    @DisplayName("testConnection 拒绝非 MqttConfig 返回 fail")
    void testConnection_wrongConfig_returnsFail() {
        var wrong = new VirtualConfig(Duration.ofSeconds(1),
            List.of(new VirtualPoint("p", VirtualMode.CONSTANT, Map.of("value", 1.0), null)));
        var result = new MqttTransport().testConnection(wrong);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("MqttConfig");
    }

    @Test
    @DisplayName("testConnection 不可达 broker 返回 fail")
    void testConnection_unreachable_returnsFail() {
        var cfg = new MqttConfig(
            "tcp://192.0.2.1:1883", "ems-test", null, null, null,
            1, true, Duration.ofSeconds(60),
            List.of(new MqttPoint("p", "x/y", "$.v", null, null)));
        var result = new MqttTransport().testConnection(cfg);
        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("isConnected 初始为 false")
    void isConnected_initial() {
        assertThat(new MqttTransport().isConnected()).isFalse();
    }

    @Test
    @DisplayName("stop 无 client 不抛异常")
    void stop_neverStarted() {
        new MqttTransport().stop();
    }
}
