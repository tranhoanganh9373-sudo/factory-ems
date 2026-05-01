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

    @Test
    @DisplayName("start 配 usernameRef 但 SecretResolver=null 抛 TransportException")
    void start_usernameRefWithoutResolver_throws() {
        var cfg = new MqttConfig(
            "tcp://broker:1883", "ems-test",
            "secret://mqtt/u", null, null,
            1, true, Duration.ofSeconds(60),
            List.of(new MqttPoint("p", "x/y", "$.v", null, null)));
        var transport = new MqttTransport();  // 无 secretResolver
        assertThatThrownBy(() -> transport.start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("usernameRef configured but no SecretResolver");
    }

    /**
     * 自签 RSA-2048 测试 CA 证书（CN=test-ca, 10 年有效期）。
     * 仅用于 SSLSocketFactory 构建测试，绝不用于真实信任链。
     */
    private static final String TEST_CA_PEM = """
        -----BEGIN CERTIFICATE-----
        MIIDBTCCAe2gAwIBAgIUdbx04jVRRP5C9vcR78XO3S/K+yQwDQYJKoZIhvcNAQEL
        BQAwEjEQMA4GA1UEAwwHdGVzdC1jYTAeFw0yNjA1MDEwNDQ4MDlaFw0zNjA0Mjgw
        NDQ4MDlaMBIxEDAOBgNVBAMMB3Rlc3QtY2EwggEiMA0GCSqGSIb3DQEBAQUAA4IB
        DwAwggEKAoIBAQCZZ5uvw6BAilb70ozS+kbonXfB6m7bIN48sT4sREcOGx7X6QMb
        fvkaONZEm360TnraRLZ+EnopVZrydAwH8A6RpJm0FM6rxSA8W1Ewo3QdckP8fRbJ
        8cHOO/Ccm3s5yY9yTUg6xBry2RQsSh9szVeLwbrTIL1OhtX7P/MLH2mlyG5HUex0
        DRf+/sOThTN4R4eHuDGNmGozYVw8pKAzQzeErsWQpdNj9xWMvvtw6uJ788/1DbRd
        naOgnvNTsWE09jqszvvBrlh6EV7CHB1aLDmLDd0xTzRjeKj0vBjFtMwfZT62Zi0f
        Lq7FLbZwnckyIMCXIGiTQRitX3lD2X7ggezRAgMBAAGjUzBRMB0GA1UdDgQWBBQi
        SHYqGORRJQIZgtAuTY1d1+S0kTAfBgNVHSMEGDAWgBQiSHYqGORRJQIZgtAuTY1d
        1+S0kTAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQAv7O04WjAG
        W39IyDOTLt9fHQjjLieUrAcrzVSadR5GgMjhngBRxCUC5xDbm7+GnUs7iTfArUdl
        TyguXNchI/0yCImf+n+u3bknF9rq6lRcoBjH5QuJ+VQh2Jh6ywmxxzy1dSy9Gjy/
        IEygXv8F3hcxFbQPmqpZqkj5JD9FLndBFv+CiRZOoGCLmG1VWdg1RdV/3lcjT4ws
        OZo3x7up9nf9H1K9hFYTO+rmAoes8mJUOlqQUlfIOya9fCPe0QCIToZ2woK7TTE+
        JBibNAAn8iTZAtE7Y/LALmLn2udPmuDnKu7k6pR4Id5fMs34CpkAGfqPPA10ea79
        dcow8eo4RS/H
        -----END CERTIFICATE-----
        """;

    @Test
    @DisplayName("buildSslSocketFactory 解析合法 PEM 返回非空 SSLSocketFactory")
    void buildSslSocketFactory_validPem_returnsFactory() {
        var factory = MqttTransport.buildSslSocketFactory(TEST_CA_PEM);
        assertThat(factory).isNotNull();
    }

    @Test
    @DisplayName("buildSslSocketFactory 解析非法 PEM 抛 TransportException")
    void buildSslSocketFactory_invalidPem_throws() {
        assertThatThrownBy(() -> MqttTransport.buildSslSocketFactory("not a real PEM cert"))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("mqtt tls cert load failed");
    }

    @Test
    @DisplayName("resolveQosArray QoS 2 — 数组全部填充为 2")
    void resolveQosArray_qos2_fillsAllSlots() {
        var cfg = new MqttConfig(
            "tcp://broker:1883", "ems-test", null, null, null,
            2, true, Duration.ofSeconds(60),
            List.of(
                new MqttPoint("p1", "a/b", "$.v", null, null),
                new MqttPoint("p2", "c/d", "$.v", null, null)
            ));
        int[] qos = MqttTransport.resolveQosArray(cfg, 2);
        assertThat(qos).containsOnly(2).hasSize(2);
    }

    @Test
    @DisplayName("start 配 tlsCaCertRef 但 SecretResolver=null 抛 TransportException")
    void start_tlsCaCertRefWithoutResolver_throws() {
        var cfg = new MqttConfig(
            "ssl://broker:8883", "ems-test",
            null, null, "secret://mqtt/tls-ca-1",
            1, true, Duration.ofSeconds(60),
            List.of(new MqttPoint("p", "x/y", "$.v", null, null)));
        var transport = new MqttTransport();  // 无 secretResolver
        assertThatThrownBy(() -> transport.start(1L, cfg, s -> {}))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("tlsCaCertRef configured but no SecretResolver");
    }
}
