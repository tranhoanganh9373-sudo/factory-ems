package com.ems.collector.transport.impl;

import com.ems.collector.cert.OpcUaCertificateStore;
import com.ems.collector.protocol.OpcUaConfig;
import com.ems.collector.protocol.OpcUaPoint;
import com.ems.collector.protocol.SecurityMode;
import com.ems.collector.protocol.SubscriptionMode;
import com.ems.collector.protocol.VirtualConfig;
import com.ems.collector.protocol.VirtualMode;
import com.ems.collector.protocol.VirtualPoint;
import com.ems.collector.secret.SecretResolver;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.TransportException;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @DisplayName("start SUBSCRIBE 点不再抛 SUBSCRIBE not implemented 异常")
    void start_withSubscribePoints_doesNotThrowSubscribeNotImplemented() {
        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:4840", SecurityMode.NONE,
            null, null, null, null, Duration.ofSeconds(1),
            List.of(new OpcUaPoint("p", "ns=2;i=1",
                SubscriptionMode.SUBSCRIBE, null, null)));

        // 没有真实 server，会因网络/连接失败而抛异常，但不应含 "SUBSCRIBE mode is not implemented"
        assertThatThrownBy(() -> new OpcUaTransport().start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageNotContaining("SUBSCRIBE mode is not implemented");
    }

    @Test
    @DisplayName("start READ+SUBSCRIBE 混合点不抛 SUBSCRIBE 拒绝错")
    void start_withMixedPoints_acceptsConfig() {
        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:4840", SecurityMode.NONE,
            null, null, null, null, Duration.ofSeconds(1),
            List.of(
                new OpcUaPoint("read1", "ns=2;i=1", SubscriptionMode.READ, null, null),
                new OpcUaPoint("sub1", "ns=2;i=2", SubscriptionMode.SUBSCRIBE, 500.0, null)));

        assertThatThrownBy(() -> new OpcUaTransport().start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageNotContaining("SUBSCRIBE mode is not implemented");
    }

    @Test
    @DisplayName("start 纯 READ 点仍走原路径不变")
    void start_withOnlyReadPoints_stillSchedulesPoller() {
        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:4840", SecurityMode.NONE,
            null, null, null, null, Duration.ofSeconds(1),
            List.of(new OpcUaPoint("r", "ns=2;i=1", SubscriptionMode.READ, null, null)));

        // 无 server，抛网络错误，但不含 SUBSCRIBE 拒绝信息
        assertThatThrownBy(() -> new OpcUaTransport().start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageNotContaining("SUBSCRIBE mode is not implemented");
    }

    @Test
    @DisplayName("resolvePublishingInterval: 全 null samplingIntervalMs 默认 1000")
    void resolvePublishingInterval_allNull_returns1000() {
        var points = List.of(
            new OpcUaPoint("a", "ns=2;i=1", SubscriptionMode.SUBSCRIBE, null, null),
            new OpcUaPoint("b", "ns=2;i=2", SubscriptionMode.SUBSCRIBE, null, null),
            new OpcUaPoint("c", "ns=2;i=3", SubscriptionMode.SUBSCRIBE, null, null));

        double result = OpcUaTransport.resolvePublishingInterval(points);

        assertThat(result).isEqualTo(1000.0);
    }

    @Test
    @DisplayName("resolvePublishingInterval: null 和有值混合 → 取有值的最小值")
    void resolvePublishingInterval_mixedNullAndValue_returnsMin() {
        var points = List.of(
            new OpcUaPoint("a", "ns=2;i=1", SubscriptionMode.SUBSCRIBE, 500.0, null),
            new OpcUaPoint("b", "ns=2;i=2", SubscriptionMode.SUBSCRIBE, null, null));

        double result = OpcUaTransport.resolvePublishingInterval(points);

        assertThat(result).isEqualTo(500.0);
    }

    @Test
    @DisplayName("resolvePublishingInterval: 多个值 → 返回最小值")
    void resolvePublishingInterval_multipleValues_returnsMin() {
        var points = List.of(
            new OpcUaPoint("a", "ns=2;i=1", SubscriptionMode.SUBSCRIBE, 2000.0, null),
            new OpcUaPoint("b", "ns=2;i=2", SubscriptionMode.SUBSCRIBE, 500.0, null));

        double result = OpcUaTransport.resolvePublishingInterval(points);

        assertThat(result).isEqualTo(500.0);
    }

    @Test
    @DisplayName("start SecurityMode.SIGN + certRef=null 抛 TransportException 含 certRef")
    void start_securityModeSign_noCertRef_throws() {
        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:99999", SecurityMode.SIGN,
            null, null, null, null, Duration.ofSeconds(1),
            List.of(new OpcUaPoint("p", "ns=2;i=1", SubscriptionMode.READ, null, null)));

        assertThatThrownBy(() -> new OpcUaTransport(mock(SecretResolver.class), mock(OpcUaCertificateStore.class))
            .start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("certRef");
    }

    @Test
    @DisplayName("start SecurityMode.SIGN_AND_ENCRYPT + certRef=null 抛 TransportException 含 certRef")
    void start_securityModeSignAndEncrypt_noCertRef_throws() {
        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:99999", SecurityMode.SIGN_AND_ENCRYPT,
            null, null, null, null, Duration.ofSeconds(1),
            List.of(new OpcUaPoint("p", "ns=2;i=1", SubscriptionMode.READ, null, null)));

        assertThatThrownBy(() -> new OpcUaTransport(mock(SecretResolver.class), mock(OpcUaCertificateStore.class))
            .start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("certRef");
    }

    @Test
    @DisplayName("resolvePem: SIGN + certRef 存在 → SecretResolver 被调用并返回 PEM")
    void resolvePem_sign_withCertRef_callsResolver() {
        // Arrange
        var certRef = "secret://opcua/client-cert";
        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:99999", SecurityMode.SIGN,
            certRef, null, null, null, Duration.ofSeconds(1),
            List.of(new OpcUaPoint("p", "ns=2;i=1", SubscriptionMode.READ, null, null)));

        var resolver = mock(SecretResolver.class);
        when(resolver.resolve(certRef)).thenReturn("pem-content");

        // Act & Assert — 在 DiscoveryClient 连接前已经 resolve，抛出的是网络错误（非 certRef 错误）
        assertThatThrownBy(() -> new OpcUaTransport(resolver, mock(OpcUaCertificateStore.class))
            .start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageNotContaining("certRef");

        // SecretResolver 必须被调用，且使用的是 certRef
        verify(resolver).resolve(certRef);
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

    @Test
    @DisplayName("qualityFromStatus: Good 返回 GOOD")
    void qualityFromStatus_good_returnsGood() {
        assertThat(OpcUaTransport.qualityFromStatus(StatusCode.GOOD)).isEqualTo(Quality.GOOD);
    }

    @Test
    @DisplayName("qualityFromStatus: Bad 返回 BAD")
    void qualityFromStatus_bad_returnsBad() {
        assertThat(OpcUaTransport.qualityFromStatus(new StatusCode(StatusCodes.Bad_DeviceFailure)))
            .isEqualTo(Quality.BAD);
    }

    @Test
    @DisplayName("qualityFromStatus: Uncertain 返回 BAD（保守策略）")
    void qualityFromStatus_uncertain_returnsBad() {
        assertThat(OpcUaTransport.qualityFromStatus(new StatusCode(StatusCodes.Uncertain_LastUsableValue)))
            .isEqualTo(Quality.BAD);
    }

    @Test
    @DisplayName("qualityFromStatus: null 返回 BAD")
    void qualityFromStatus_null_returnsBad() {
        assertThat(OpcUaTransport.qualityFromStatus(null)).isEqualTo(Quality.BAD);
    }
}
