package com.ems.collector.transport.impl;

import com.ems.collector.cert.OpcUaCertificateLoader;
import com.ems.collector.cert.OpcUaCertificateStore;
import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.protocol.OpcUaConfig;
import com.ems.collector.protocol.OpcUaPoint;
import com.ems.collector.protocol.SecurityMode;
import com.ems.collector.protocol.SubscriptionMode;
import com.ems.collector.secret.SecretResolver;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.SampleSink;
import com.ems.collector.transport.TestResult;
import com.ems.collector.transport.Transport;
import com.ems.collector.transport.TransportException;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.subscriptions.ManagedSubscription;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.client.security.ClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/**
 * OPC UA Transport — Phase 5 Task 5.3 + SUBSCRIBE 扩展。
 *
 * <p>支持：
 * <ul>
 *   <li>SecurityMode：NONE、SIGN、SIGN_AND_ENCRYPT（后两者需配置 certRef + OpcUaCertificateStore）。
 *   <li>SubscriptionMode.READ：轮询模式，按 pollInterval 调度。
 *   <li>SubscriptionMode.SUBSCRIBE：订阅模式，使用 {@link ManagedSubscription} 推送。
 *   <li>同一 channel 可混合 READ + SUBSCRIBE 测点。
 * </ul>
 *
 * <p>用户名密码通过 {@link SecretResolver} 解析；缺失 SecretResolver 但配置了 usernameRef
 * 时早 fail（与 MqttTransport 一致）。
 */
public final class OpcUaTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(OpcUaTransport.class);

    private final SecretResolver secretResolver;
    private final OpcUaCertificateStore certStore;

    private OpcUaClient client;
    private ManagedSubscription subscription;
    private ScheduledExecutorService poller;
    private volatile boolean connected = false;
    private Long channelId;

    public OpcUaTransport(SecretResolver secretResolver, OpcUaCertificateStore certStore) {
        this.secretResolver = secretResolver;
        this.certStore = certStore;
    }

    /**
     * 便利构造器，仅适用于 SecurityMode.NONE 配置或测试场景。
     * SIGN/SIGN_AND_ENCRYPT 模式必须使用 {@link #OpcUaTransport(SecretResolver, OpcUaCertificateStore)}，
     * 否则 start() 会抛 TransportException。
     */
    public OpcUaTransport(SecretResolver secretResolver) {
        this(secretResolver, null);
    }

    /**
     * 仅供测试用的无参构造器。仅适用于 SecurityMode.NONE。
     */
    public OpcUaTransport() {
        this(null, null);
    }

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        if (!(config instanceof OpcUaConfig cfg)) {
            throw new TransportException("expected OpcUaConfig, got "
                + (config == null ? "null" : config.getClass().getSimpleName()));
        }
        this.channelId = channelId;

        // SIGN / SIGN_AND_ENCRYPT 需要 certRef。
        if (cfg.securityMode() != SecurityMode.NONE && cfg.certRef() == null) {
            throw new TransportException(
                "OPC UA SecurityMode " + cfg.securityMode() + " requires certRef "
                    + "(channelId=" + channelId + ")");
        }

        // usernameRef 配了但 SecretResolver 缺失 → 早 fail（与 MqttTransport 一致）。
        if (cfg.usernameRef() != null && secretResolver == null) {
            throw new TransportException(
                "usernameRef configured but no SecretResolver injected (channelId="
                    + channelId + ")");
        }

        // 若为 SIGN/SIGN_AND_ENCRYPT，提前解析客户端证书（fail-fast，避免连 server 后才报错）。
        OpcUaCertificateLoader.ClientKeyMaterial keyMaterial = null;
        if (cfg.securityMode() != SecurityMode.NONE) {
            String pem = secretResolver.resolve(cfg.certRef());
            String password = cfg.certPasswordRef() != null
                ? secretResolver.resolve(cfg.certPasswordRef())
                : null;
            keyMaterial = OpcUaCertificateLoader.loadClientKeyMaterial(pem, password);
            log.info("opcua client cert loaded channel={} subject={}",
                channelId, keyMaterial.certificate().getSubjectX500Principal().getName());
        }

        final OpcUaCertificateLoader.ClientKeyMaterial finalKeyMaterial = keyMaterial;

        try {
            var endpoints = DiscoveryClient.getEndpoints(cfg.endpointUrl()).get();
            var ep = endpoints.stream()
                .filter(e -> matchesSecurity(e, cfg.securityMode()))
                .findFirst()
                .orElseThrow(() -> new TransportException(
                    "no matching endpoint for security mode " + cfg.securityMode()
                        + " at " + cfg.endpointUrl()));

            var clientConfigBuilder = OpcUaClientConfig.builder()
                .setEndpoint(ep)
                .setApplicationName(LocalizedText.english("EMS Collector"))
                .setApplicationUri("urn:ems:collector")
                .setRequestTimeout(uint(10_000));

            if (finalKeyMaterial != null) {
                clientConfigBuilder
                    .setKeyPair(finalKeyMaterial.keyPair())
                    .setCertificate(finalKeyMaterial.certificate())
                    .setCertificateValidator(buildCertificateValidator());
                log.info("opcua tls configured channel={} mode={}", channelId, cfg.securityMode());
            }

            if (cfg.usernameRef() != null) {
                String username = secretResolver.resolve(cfg.usernameRef());
                String password = cfg.passwordRef() != null
                    ? secretResolver.resolve(cfg.passwordRef())
                    : "";
                clientConfigBuilder.setIdentityProvider(new UsernameProvider(username, password));
                log.info("opcua identity provider channel={} username={}", channelId, username);
            }

            client = OpcUaClient.create(clientConfigBuilder.build());
            client.connect().get();
            connected = true;
            log.info("opcua connected channel={} endpoint={}", channelId, cfg.endpointUrl());
        } catch (TransportException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException("opcua connect failed: " + e.getMessage(), e);
        }

        setupSubscription(cfg, sink);

        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "opcua-" + channelId);
            t.setDaemon(true);
            return t;
        });
        boolean hasReadPoints = cfg.points().stream()
            .anyMatch(p -> p.mode() == SubscriptionMode.READ);
        if (hasReadPoints && cfg.pollInterval() != null) {
            poller.scheduleAtFixedRate(() -> tick(cfg, sink),
                0, cfg.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void setupSubscription(OpcUaConfig cfg, SampleSink sink) {
        var subscribePoints = cfg.points().stream()
            .filter(p -> p.mode() == SubscriptionMode.SUBSCRIBE)
            .toList();
        if (subscribePoints.isEmpty()) return;

        double publishingMs = resolvePublishingInterval(subscribePoints);
        try {
            subscription = ManagedSubscription.create(client, publishingMs);
            for (var p : subscribePoints) {
                var nodeId = NodeId.parse(p.nodeId());
                var item = subscription.createDataItem(nodeId);
                item.addDataValueListener(dv -> handleSubscriptionValue(p, dv, sink));
            }
            log.info("opcua subscription created channel={} items={} publishingMs={}",
                channelId, subscribePoints.size(), publishingMs);
        } catch (UaException e) {
            throw new TransportException("opcua subscription setup failed: " + e.getMessage(), e);
        }
    }

    /**
     * 计算 SUBSCRIBE 测点集合的 publishing interval（毫秒）。
     * 取所有 samplingIntervalMs 中的最小值；null 视为默认 1000 ms。
     */
    static double resolvePublishingInterval(List<OpcUaPoint> subscribePoints) {
        return subscribePoints.stream()
            .map(p -> p.samplingIntervalMs() != null ? p.samplingIntervalMs() : 1000.0)
            .min(Double::compare)
            .orElse(1000.0);
    }

    private void handleSubscriptionValue(OpcUaPoint p, DataValue dv, SampleSink sink) {
        try {
            sink.accept(new Sample(channelId, p.key(), Instant.now(),
                dv.getValue().getValue(), Quality.GOOD,
                Map.of("source", "subscription")));
        } catch (Throwable t) {
            log.warn("opcua subscription value handle failed channel={} key={}: {}",
                channelId, p.key(), t.toString());
        }
    }

    private void tick(OpcUaConfig cfg, SampleSink sink) {
        try {
            for (var p : cfg.points()) {
                if (p.mode() != SubscriptionMode.READ) continue;
                pollOne(p, sink);
            }
        } catch (Throwable t) {
            log.warn("opcua tick failed channel={}: {}", channelId, t.toString());
        }
    }

    private void pollOne(OpcUaPoint p, SampleSink sink) {
        var startMs = System.currentTimeMillis();
        try {
            var nodeId = NodeId.parse(p.nodeId());
            var dv = client.readValue(0, TimestampsToReturn.Both, nodeId).get();
            sink.accept(new Sample(channelId, p.key(), Instant.now(),
                dv.getValue().getValue(), Quality.GOOD,
                Map.of("latencyMs", String.valueOf(System.currentTimeMillis() - startMs))));
        } catch (Exception e) {
            log.warn("opcua read channel={} nodeId={} failed: {}",
                channelId, p.nodeId(), e.getMessage());
        }
    }

    /**
     * 构建基于 {@link OpcUaCertificateStore} 的服务端证书校验器。
     * certStore 必须非 null（由 start() 的前置断言保证，仅 SIGN/SIGN_AND_ENCRYPT 模式进入此处）。
     */
    private ClientCertificateValidator buildCertificateValidator() {
        if (certStore == null) {
            throw new TransportException(
                "certStore required for SIGN mode (channelId=" + channelId + ")");
        }
        return new ClientCertificateValidator() {
            @Override
            public void validateCertificateChain(List<X509Certificate> chain) throws UaException {
                X509Certificate serverCert = chain.get(0);
                try {
                    if (!certStore.isTrusted(serverCert)) {
                        String thumbprint = certStore.thumbprint(serverCert);
                        throw new TransportException(
                            "server certificate not trusted: "
                                + serverCert.getSubjectX500Principal().getName()
                                + " thumbprint=" + thumbprint
                                + " — use /api/opcua/certs/approve to trust it");
                    }
                } catch (TransportException e) {
                    throw e;
                } catch (Exception e) {
                    throw new TransportException("cert trust check failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void validateCertificateChain(
                    List<X509Certificate> chain, String applicationUri, String... validHostNames)
                    throws UaException {
                validateCertificateChain(chain);
            }
        };
    }

    private boolean matchesSecurity(EndpointDescription ep, SecurityMode mode) {
        var policy = ep.getSecurityPolicyUri();
        return switch (mode) {
            case NONE -> policy.contains("None");
            case SIGN -> policy.contains("Basic256Sha256")
                && ep.getSecurityMode() == MessageSecurityMode.Sign;
            case SIGN_AND_ENCRYPT -> policy.contains("Basic256Sha256")
                && ep.getSecurityMode() == MessageSecurityMode.SignAndEncrypt;
        };
    }

    @Override
    public void stop() {
        connected = false;
        if (subscription != null) {
            try {
                subscription.delete();
            } catch (Exception e) {
                log.warn("opcua subscription delete channel={} error: {}", channelId, e.toString());
            }
        }
        if (poller != null) poller.shutdownNow();
        if (client != null) {
            try {
                client.disconnect().get();
            } catch (Exception e) {
                log.warn("opcua disconnect channel={} error: {}", channelId, e.toString());
            }
        }
        log.info("opcua transport stopped channel={}", channelId);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public TestResult testConnection(ChannelConfig config) {
        if (!(config instanceof OpcUaConfig cfg)) {
            return TestResult.fail("expected OpcUaConfig");
        }
        var startMs = System.currentTimeMillis();
        try {
            DiscoveryClient.getEndpoints(cfg.endpointUrl()).get(5, TimeUnit.SECONDS);
            return TestResult.ok(System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            return TestResult.fail("discovery failed: " + e.getMessage());
        }
    }
}
