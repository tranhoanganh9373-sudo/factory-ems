package com.ems.collector.transport.impl;

import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.protocol.MqttConfig;
import com.ems.collector.protocol.MqttPoint;
import com.ems.collector.secret.SecretResolver;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.SampleSink;
import com.ems.collector.transport.TestResult;
import com.ems.collector.transport.Transport;
import com.ems.collector.transport.TransportException;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/**
 * MQTT transport — 订阅 broker topic，按 JSONPath 提取 payload 字段为 Sample。
 *
 * <p>单实例对应单 channel：start 同步连接 + 订阅，messageArrived 异步推送 Sample。
 * 自动重连开启，重连后会重新订阅 topic 集合。
 */
public final class MqttTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(MqttTransport.class);

    private static final long CONNECT_TIMEOUT_MS = 10_000L;
    private static final long SUBSCRIBE_TIMEOUT_MS = 10_000L;
    private static final long DISCONNECT_TIMEOUT_MS = 2_000L;
    private static final int TEST_CONNECT_TIMEOUT_S = 5;
    private static final long TEST_DISCONNECT_TIMEOUT_MS = 2_000L;
    private static final long TEST_CONNECT_WAIT_MS = 5_000L;

    private final SecretResolver secretResolver;
    private final Configuration jsonPathCfg = Configuration.defaultConfiguration()
        .addOptions(Option.SUPPRESS_EXCEPTIONS);

    private MqttAsyncClient client;
    private volatile boolean connected = false;
    private Long channelId;

    public MqttTransport(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    public MqttTransport() {
        this(null);
    }

    /**
     * 根据 MqttConfig 构建 MqttConnectOptions，包含 LWT 设置（若已配置）。
     * Package-private for testing.
     */
    static MqttConnectOptions buildConnectOptions(MqttConfig cfg, SecretResolver secretResolver, Long channelId) {
        var opts = new MqttConnectOptions();
        opts.setCleanSession(cfg.cleanSession());
        opts.setKeepAliveInterval((int) cfg.keepAlive().toSeconds());
        opts.setAutomaticReconnect(true);
        if (cfg.usernameRef() != null) {
            if (secretResolver == null) {
                throw new TransportException(
                    "usernameRef configured but no SecretResolver injected (channelId=" + channelId + ")");
            }
            opts.setUserName(secretResolver.resolve(cfg.usernameRef()));
            if (cfg.passwordRef() != null) {
                opts.setPassword(secretResolver.resolve(cfg.passwordRef()).toCharArray());
            }
        }
        if (cfg.tlsCaCertRef() != null) {
            if (secretResolver == null) {
                throw new TransportException(
                    "tlsCaCertRef configured but no SecretResolver injected (channelId=" + channelId + ")");
            }
            String pem = secretResolver.resolve(cfg.tlsCaCertRef());
            opts.setSocketFactory(buildSslSocketFactory(pem));
            log.info("mqtt tls enabled channel={}", channelId);
        }
        if (cfg.lastWillTopic() != null && !cfg.lastWillTopic().isBlank()
            && cfg.lastWillPayload() != null) {
            opts.setWill(
                cfg.lastWillTopic(),
                cfg.lastWillPayload().getBytes(StandardCharsets.UTF_8),
                cfg.lastWillQos(),
                cfg.lastWillRetained()
            );
        }
        return opts;
    }

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        if (!(config instanceof MqttConfig cfg)) {
            throw new TransportException("expected MqttConfig, got "
                + (config == null ? "null" : config.getClass().getSimpleName()));
        }
        this.channelId = channelId;
        var opts = buildConnectOptions(cfg, secretResolver, channelId);
        try {
            client = new MqttAsyncClient(cfg.brokerUrl(), cfg.clientId(), new MemoryPersistence());
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectionLost(Throwable t) {
                    connected = false;
                    log.warn("mqtt connection lost channel={}: {}", channelId, t.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage msg) {
                    try {
                        handleMessage(topic, msg, cfg, sink);
                    } catch (Throwable t) {
                        log.warn("mqtt handleMessage failed channel={} topic={}: {}",
                            channelId, topic, t.toString());
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken t) {
                    // publishing not used
                }

                @Override
                public void connectComplete(boolean reconnect, String uri) {
                    connected = true;
                    log.info("mqtt connected channel={} uri={} reconnect={}",
                        channelId, uri, reconnect);
                    if (reconnect) {
                        resubscribe(cfg);
                    }
                }
            });
            client.connect(opts).waitForCompletion(CONNECT_TIMEOUT_MS);
            subscribe(cfg);
        } catch (Exception e) {
            throw new TransportException("mqtt connect failed: " + e.getMessage(), e);
        }
    }

    private void subscribe(MqttConfig cfg) throws MqttException {
        var topics = cfg.points().stream().map(MqttPoint::topic).distinct().toArray(String[]::new);
        client.subscribe(topics, resolveQosArray(cfg, topics.length)).waitForCompletion(SUBSCRIBE_TIMEOUT_MS);
    }

    /**
     * 为给定 channel 配置构建 QoS 数组（每个 topic 一个槽位，全部填充 cfg.qos()）。
     * Package-private for testing.
     */
    static int[] resolveQosArray(MqttConfig cfg, int length) {
        var qos = new int[length];
        Arrays.fill(qos, cfg.qos());
        return qos;
    }

    private void resubscribe(MqttConfig cfg) {
        try {
            subscribe(cfg);
        } catch (Exception e) {
            log.error("mqtt resubscribe failed channel={}: {}", channelId, e.toString());
        }
    }

    private void handleMessage(String topic, MqttMessage msg, MqttConfig cfg, SampleSink sink) {
        var payload = new String(msg.getPayload(), StandardCharsets.UTF_8);
        DocumentContext doc;
        try {
            doc = JsonPath.using(jsonPathCfg).parse(payload);
        } catch (Exception e) {
            log.warn("mqtt non-json payload channel={} topic={}: {}",
                channelId, topic, e.getMessage());
            return;
        }
        for (var p : cfg.points()) {
            if (!topicMatches(p.topic(), topic)) {
                continue;
            }
            Object value = doc.read(p.jsonPath(), Object.class);
            if (value == null) {
                continue;
            }
            Instant ts = Instant.now();
            if (p.timestampJsonPath() != null) {
                try {
                    String tsStr = doc.read(p.timestampJsonPath(), String.class);
                    if (tsStr != null) {
                        ts = Instant.parse(tsStr);
                    }
                } catch (Exception ignored) {
                    // fall back to Instant.now()
                }
            }
            sink.accept(new Sample(channelId, p.key(), ts, value, Quality.GOOD,
                Map.of("topic", topic)));
        }
    }

    /**
     * 由 PEM 编码的 CA 证书构建一组单证书 KeyStore，并据此初始化 TLSv1.2 SSLContext，
     * 返回可用于 broker 单向认证的 SSLSocketFactory。
     *
     * <p>仅支持单证书一向 TLS（验证 broker），不支持客户端证书 / mTLS。
     * Package-private for testing.
     */
    static SSLSocketFactory buildSslSocketFactory(String pemCert) {
        try {
            var bytes = pemCert.getBytes(StandardCharsets.UTF_8);
            var certFactory = CertificateFactory.getInstance("X.509");
            var cert = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(bytes));
            var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("ca", cert);
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            var ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new TransportException("mqtt tls cert load failed: " + e.getMessage(), e);
        }
    }

    /**
     * MQTT topic 模式匹配（仅支持 {@code +} 单层、{@code #} 多层后缀）。
     * Package-private for testing.
     */
    static boolean topicMatches(String pattern, String topic) {
        var pp = pattern.split("/");
        var tp = topic.split("/");
        for (int i = 0; i < pp.length; i++) {
            if ("#".equals(pp[i])) {
                // MQTT '#' 必须匹配 >=1 层；位于 prefix 0 时也匹配整 topic
                return tp.length > i;
            }
            if (i >= tp.length) {
                return false;
            }
            if ("+".equals(pp[i])) {
                continue;
            }
            if (!pp[i].equals(tp[i])) {
                return false;
            }
        }
        return pp.length == tp.length;
    }

    @Override
    public void stop() {
        connected = false;
        if (client != null) {
            try {
                client.disconnect().waitForCompletion(DISCONNECT_TIMEOUT_MS);
            } catch (Exception e) {
                log.warn("mqtt disconnect channel={} error: {}", channelId, e.toString());
            }
        }
        log.info("mqtt transport stopped channel={}", channelId);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public TestResult testConnection(ChannelConfig config) {
        if (!(config instanceof MqttConfig cfg)) {
            return TestResult.fail("expected MqttConfig");
        }
        var startMs = System.currentTimeMillis();
        try {
            var c = new MqttAsyncClient(cfg.brokerUrl(), cfg.clientId() + "-test",
                new MemoryPersistence());
            var opts = new MqttConnectOptions();
            opts.setConnectionTimeout(TEST_CONNECT_TIMEOUT_S);
            c.connect(opts).waitForCompletion(TEST_CONNECT_WAIT_MS);
            c.disconnect().waitForCompletion(TEST_DISCONNECT_TIMEOUT_MS);
            return TestResult.ok(System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            return TestResult.fail("mqtt test failed: " + e.getMessage());
        }
    }
}
