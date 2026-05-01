package com.ems.collector.transport.impl;

import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.protocol.OpcUaConfig;
import com.ems.collector.protocol.OpcUaPoint;
import com.ems.collector.protocol.SecurityMode;
import com.ems.collector.protocol.SubscriptionMode;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.SampleSink;
import com.ems.collector.transport.TestResult;
import com.ems.collector.transport.Transport;
import com.ems.collector.transport.TransportException;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
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
 * <p>SUBSCRIBE 模式由 Task 5.4 实现；证书审批 REST 由 Task 5.5 实现。
 * 当前仅支持 SecurityMode.NONE / SIGN / SIGN_AND_ENCRYPT 的 endpoint 选择，证书加载留待 5.5。
 */
public final class OpcUaTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(OpcUaTransport.class);

    private OpcUaClient client;
    private ScheduledExecutorService poller;
    private volatile boolean connected = false;
    private Long channelId;

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        if (!(config instanceof OpcUaConfig cfg)) {
            throw new TransportException("expected OpcUaConfig, got "
                + (config == null ? "null" : config.getClass().getSimpleName()));
        }
        this.channelId = channelId;
        try {
            var endpoints = DiscoveryClient.getEndpoints(cfg.endpointUrl()).get();
            var ep = endpoints.stream()
                .filter(e -> matchesSecurity(e, cfg.securityMode()))
                .findFirst()
                .orElseThrow(() -> new TransportException(
                    "no matching endpoint for security mode " + cfg.securityMode()
                        + " at " + cfg.endpointUrl()));
            client = OpcUaClient.create(OpcUaClientConfig.builder()
                .setEndpoint(ep)
                .setApplicationName(LocalizedText.english("EMS Collector"))
                .setApplicationUri("urn:ems:collector")
                .setRequestTimeout(uint(10_000))
                .build());
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
