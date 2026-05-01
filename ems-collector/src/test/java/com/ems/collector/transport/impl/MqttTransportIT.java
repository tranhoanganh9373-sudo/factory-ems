package com.ems.collector.transport.impl;

import com.ems.collector.protocol.MqttConfig;
import com.ems.collector.protocol.MqttPoint;
import com.ems.collector.secret.SecretResolver;
import com.ems.collector.transport.Sample;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "DOCKER_TC_AVAILABLE", matches = "true")
@DisplayName("MqttTransportIT (Docker)")
class MqttTransportIT {

    @Container
    static HiveMQContainer broker = new HiveMQContainer(
        DockerImageName.parse("hivemq/hivemq-ce:2024.3"));

    @Test
    @DisplayName("接收消息后通过 JsonPath 提取并推送 Sample")
    void receivesAndExtractsViaJsonPath() throws Exception {
        var resolver = noopResolver();
        var url = "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883);
        var cfg = new MqttConfig(url, "ems-test", null, null, null,
            1, true, Duration.ofSeconds(60),
            List.of(new MqttPoint("temp", "sensors/+/temp", "$.value", "C", null)));

        var samples = new ConcurrentLinkedQueue<Sample>();
        var transport = new MqttTransport(resolver);
        try {
            transport.start(1L, cfg, samples::add);

            var pub = new MqttClient(url, "publisher-" + System.nanoTime(), new MemoryPersistence());
            pub.connect();
            pub.publish("sensors/factory1/temp",
                new MqttMessage("{\"value\":23.5}".getBytes()));
            pub.disconnect();
            pub.close();

            await().atMost(5, TimeUnit.SECONDS).until(() -> !samples.isEmpty());
            var s = samples.peek();
            assertThat(s.value()).isEqualTo(23.5);
            assertThat(s.pointKey()).isEqualTo("temp");
            assertThat(s.tags()).containsEntry("topic", "sensors/factory1/temp");
        } finally {
            transport.stop();
        }
    }

    @Test
    @DisplayName("QoS 2 — 接收消息后通过 JsonPath 提取并推送 Sample")
    void receivesAndExtractsViaJsonPath_qos2() throws Exception {
        var resolver = noopResolver();
        var url = "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883);
        var cfg = new MqttConfig(url, "ems-test-qos2", null, null, null,
            2, true, Duration.ofSeconds(60),
            List.of(new MqttPoint("power", "sensors/+/power", "$.value", "kW", null)));

        var samples = new ConcurrentLinkedQueue<Sample>();
        var transport = new MqttTransport(resolver);
        try {
            transport.start(1L, cfg, samples::add);

            var pub = new MqttClient(url, "publisher-qos2-" + System.nanoTime(), new MemoryPersistence());
            pub.connect();
            pub.publish("sensors/factory1/power",
                new MqttMessage("{\"value\":42.0}".getBytes()));
            pub.disconnect();
            pub.close();

            await().atMost(5, TimeUnit.SECONDS).until(() -> !samples.isEmpty());
            var s = samples.peek();
            assertThat(s.value()).isEqualTo(42.0);
            assertThat(s.pointKey()).isEqualTo("power");
            assertThat(s.tags()).containsEntry("topic", "sensors/factory1/power");
        } finally {
            transport.stop();
        }
    }

    private SecretResolver noopResolver() {
        return new SecretResolver() {
            @Override public String resolve(String r) { return ""; }
            @Override public boolean exists(String r) { return false; }
            @Override public void write(String r, String v) {}
            @Override public void delete(String r) {}
            @Override public List<String> listRefs() { return List.of(); }
        };
    }
}
