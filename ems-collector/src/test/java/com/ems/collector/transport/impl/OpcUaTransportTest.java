package com.ems.collector.transport.impl;

import com.ems.collector.protocol.OpcUaConfig;
import com.ems.collector.protocol.OpcUaPoint;
import com.ems.collector.protocol.SecurityMode;
import com.ems.collector.protocol.SubscriptionMode;
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

/**
 * OpcUaTransport 单元测试 — 不依赖 OPC UA server。
 *
 * <p>IT（端到端 READ 验证）依赖 Milo example server，0.6.13 maven central 未发布该 artifact，
 * 暂跳过。Plan 第 2086-2135 行的 IT 待 Phase 7 引入合适 fixture 后补回。
 */
@DisplayName("OpcUaTransport (无 server)")
class OpcUaTransportTest {

    @Test
    @DisplayName("start 拒绝非 OpcUaConfig 抛 TransportException")
    void start_wrongConfig_throws() {
        var transport = new OpcUaTransport();
        var wrong = new VirtualConfig(Duration.ofSeconds(1),
            List.of(new VirtualPoint("p", VirtualMode.CONSTANT,
                Map.of("value", 1.0), null)));

        assertThatThrownBy(() -> transport.start(1L, wrong, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("OpcUaConfig");
    }

    @Test
    @DisplayName("testConnection 拒绝非 OpcUaConfig 返回 fail")
    void testConnection_wrongConfig_returnsFail() {
        var wrong = new VirtualConfig(Duration.ofSeconds(1),
            List.of(new VirtualPoint("p", VirtualMode.CONSTANT,
                Map.of("value", 1.0), null)));

        var result = new OpcUaTransport().testConnection(wrong);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("OpcUaConfig");
    }

    @Test
    @DisplayName("testConnection 不可达 endpoint 返回 fail")
    void testConnection_unreachable_returnsFail() {
        var cfg = new OpcUaConfig(
            "opc.tcp://192.0.2.1:4840", SecurityMode.NONE,
            null, null, null, null, null,
            List.of(new OpcUaPoint("ct", "ns=2;i=2", SubscriptionMode.READ, null, null)));

        var result = new OpcUaTransport().testConnection(cfg);

        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("start SUBSCRIBE 模式抛 TransportException")
    void start_subscribePoint_throws() {
        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:4840", SecurityMode.NONE,
            null, null, null, null, Duration.ofSeconds(1),
            List.of(new OpcUaPoint("p", "ns=2;i=1",
                SubscriptionMode.SUBSCRIBE, null, null)));

        assertThatThrownBy(() -> new OpcUaTransport().start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("SUBSCRIBE");
    }

    @Test
    @DisplayName("start SecurityMode.SIGN 抛 TransportException")
    void start_securityModeSign_throws() {
        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:99999", SecurityMode.SIGN,
            null, null, null, null, Duration.ofSeconds(1),
            List.of(new OpcUaPoint("p", "ns=2;i=1", SubscriptionMode.READ, null, null)));

        assertThatThrownBy(() -> new OpcUaTransport().start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("not implemented");
    }

    @Test
    @DisplayName("start SecurityMode.SIGN_AND_ENCRYPT 抛 TransportException")
    void start_securityModeSignAndEncrypt_throws() {
        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:99999", SecurityMode.SIGN_AND_ENCRYPT,
            null, null, null, null, Duration.ofSeconds(1),
            List.of(new OpcUaPoint("p", "ns=2;i=1", SubscriptionMode.READ, null, null)));

        assertThatThrownBy(() -> new OpcUaTransport().start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("not implemented");
    }

    @Test
    @DisplayName("start usernameRef 配但无 SecretResolver 抛 TransportException")
    void start_usernameRefWithoutResolver_throws() {
        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:99999", SecurityMode.NONE,
            null, null, "secret://opcua/u", null, Duration.ofSeconds(1),
            List.of(new OpcUaPoint("p", "ns=2;i=1", SubscriptionMode.READ, null, null)));

        assertThatThrownBy(() -> new OpcUaTransport().start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("SecretResolver");
    }

    @Test
    @DisplayName("isConnected 初始为 false")
    void isConnected_initial_returnsFalse() {
        assertThat(new OpcUaTransport().isConnected()).isFalse();
    }

    @Test
    @DisplayName("stop 无 client 不抛异常")
    void stop_neverStarted_doesNotThrow() {
        new OpcUaTransport().stop();
    }
}
