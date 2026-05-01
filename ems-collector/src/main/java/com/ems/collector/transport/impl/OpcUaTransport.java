package com.ems.collector.transport.impl;

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
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/**
 * OPC UA Transport — Phase 5 Task 5.3：READ 模式实现。
 *
 * <p>v1 限制：
 * <ul>
 *   <li>仅支持 {@link SecurityMode#NONE}；SIGN / SIGN_AND_ENCRYPT 需要证书 store 集成，留 v2。
 *   <li>仅支持 {@link SubscriptionMode#READ}；SUBSCRIBE 留 v2。
 * </ul>
 *
 * <p>用户名密码通过 {@link SecretResolver} 解析；缺失 SecretResolver 但配置了 usernameRef
 * 时早 fail（与 MqttTransport 一致）。
 */
public final class OpcUaTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(OpcUaTransport.class);

    private final SecretResolver secretResolver;

    private OpcUaClient client;
    private ScheduledExecutorService poller;
    private volatile boolean connected = false;
    private Long channelId;

    public OpcUaTransport(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    public OpcUaTransport() {
        this(null);
    }

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        if (!(config instanceof OpcUaConfig cfg)) {
            throw new TransportException("expected OpcUaConfig, got "
                + (config == null ? "null" : config.getClass().getSimpleName()));
        }
        this.channelId = channelId;

        // v1 限制：仅支持 SecurityMode.NONE。SIGN / SIGN_AND_ENCRYPT 需要证书 store 集成，留 v2。
        if (cfg.securityMode() != SecurityMode.NONE) {
            throw new TransportException(
                "OPC UA SecurityMode " + cfg.securityMode() + " is not implemented in v1; "
                    + "use SecurityMode.NONE (channelId=" + channelId + ")");
        }

        // v1 仅实现 READ 模式；SUBSCRIBE 留待 v2。早 fail 比静默丢数据更安全。
        boolean hasSubscribe = cfg.points().stream()
            .anyMatch(p -> p.mode() == SubscriptionMode.SUBSCRIBE);
        if (hasSubscribe) {
            throw new TransportException(
                "OPC UA SUBSCRIBE mode is not implemented in v1; "
                    + "configure all points with mode=READ (channelId=" + channelId + ")");
        }

        // usernameRef 配了但 SecretResolver 缺失 → 早 fail（与 MqttTransport 一致）。
        if (cfg.usernameRef() != null && secretResolver == null) {
            throw new TransportException(
                "usernameRef configured but no SecretResolver injected (channelId="
                    + channelId + ")");
        }

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

        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "opcua-" + channelId);
            t.setDaemon(true);
            return t;
        });
        if (cfg.pollInterval() != null) {
            poller.scheduleAtFixedRate(() -> tick(cfg, sink),
                0, cfg.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
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

    private boolean matchesSecurity(EndpointDescription ep, SecurityMode mode) {
        var policy = ep.getSecurityPolicyUri();
        return switch (mode) {
            case NONE -> policy.contains("None");
            case SIGN -> policy.contains("Basic256Sha256")
                && ep.getSecurityMode().getValue() == 2;
            case SIGN_AND_ENCRYPT -> policy.contains("Basic256Sha256")
                && ep.getSecurityMode().getValue() == 3;
        };
    }

    @Override
    public void stop() {
        connected = false;
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
