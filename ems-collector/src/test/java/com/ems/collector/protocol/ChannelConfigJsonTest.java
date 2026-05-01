package com.ems.collector.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChannelConfig Jackson 多态序列化")
class ChannelConfigJsonTest {
    private final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("OpcUaConfig 序列化与反序列化保持类型")
    void serializeDeserialize_opcUaConfig_preservesType() throws Exception {
        var cfg = new OpcUaConfig(
            "opc.tcp://test:62541", SecurityMode.NONE, null, null, null, null,
            Duration.ofSeconds(5),
            List.of(new OpcUaPoint("temp", "ns=2;s=Tag1", SubscriptionMode.SUBSCRIBE, 1000.0, "C"))
        );
        var json = om.writeValueAsString(cfg);
        assertThat(json).contains("\"protocol\":\"OPC_UA\"");
        var parsed = om.readValue(json, ChannelConfig.class);
        assertThat(parsed).isInstanceOf(OpcUaConfig.class);
        assertThat(((OpcUaConfig) parsed).points()).hasSize(1);
    }

    @Test
    @DisplayName("VirtualConfig 序列化与反序列化保持类型")
    void serializeDeserialize_virtualConfig_preservesType() throws Exception {
        var cfg = new VirtualConfig(
            Duration.ofSeconds(1),
            List.of(new VirtualPoint("p1", VirtualMode.SINE,
                Map.of("amplitude", 10.0, "periodSec", 60.0), "kW"))
        );
        var parsed = om.readValue(om.writeValueAsString(cfg), ChannelConfig.class);
        assertThat(parsed).isInstanceOf(VirtualConfig.class);
    }

    @Test
    @DisplayName("MqttConfig 序列化与反序列化保持类型")
    void serializeDeserialize_mqttConfig_preservesType() throws Exception {
        var cfg = new MqttConfig(
            "tcp://broker:1883", "ems-collector-1",
            "secret://mqtt/u", "secret://mqtt/p",
            null, 1, true, Duration.ofSeconds(60),
            null, null, 0, false,
            List.of(new MqttPoint("temp", "sensors/+/t", "$.value", "C", null))
        );
        var parsed = om.readValue(om.writeValueAsString(cfg), ChannelConfig.class);
        assertThat(parsed).isInstanceOf(MqttConfig.class);
    }

    @Test
    @DisplayName("ModbusTcpConfig 序列化保留 protocol 字段")
    void serializeDeserialize_modbusTcpConfig_preservesType() throws Exception {
        var cfg = new ModbusTcpConfig("127.0.0.1", 502, 1,
            Duration.ofSeconds(5), Duration.ofMillis(500),
            List.of(new ModbusPoint("p1", "HOLDING", 0, 1, "INT16",
                "BIG_ENDIAN", null, "kW")));
        var json = om.writeValueAsString(cfg);
        assertThat(json).contains("\"protocol\":\"MODBUS_TCP\"");
        var parsed = om.readValue(json, ChannelConfig.class);
        assertThat(parsed).isInstanceOf(ModbusTcpConfig.class);
    }

    @Test
    @DisplayName("ModbusRtuConfig 反序列化通过 protocol 鉴别")
    void deserialize_modbusRtuConfigJson_resolvesToCorrectType() throws Exception {
        var json = """
            {"protocol":"MODBUS_RTU","serialPort":"/dev/ttyUSB0","baudRate":9600,
             "dataBits":8,"stopBits":1,"parity":"NONE","unitId":1,
             "pollInterval":"PT5S","timeout":"PT0.5S",
             "points":[{"key":"p1","registerKind":"HOLDING","address":0,"quantity":1,
                        "dataType":"INT16","byteOrder":null,"scale":null,"unit":null}]}
            """;
        var parsed = om.readValue(json, ChannelConfig.class);
        assertThat(parsed).isInstanceOf(ModbusRtuConfig.class);
    }

    @Test
    @DisplayName("MqttConfig 旧格式 JSON（无 LWT 字段）能向后兼容反序列化")
    void deserialize_mqttConfig_legacyJson_noLwtFields_succeeds() throws Exception {
        var json = """
            {"protocol":"MQTT","brokerUrl":"tcp://b:1883","clientId":"c",
             "qos":1,"cleanSession":true,"keepAlive":"PT60S",
             "points":[{"key":"t","topic":"a/b","jsonPath":"$.v"}]}
            """;
        var parsed = om.readValue(json, ChannelConfig.class);
        assertThat(parsed).isInstanceOf(MqttConfig.class);
        var mqtt = (MqttConfig) parsed;
        assertThat(mqtt.lastWillTopic()).isNull();
        assertThat(mqtt.lastWillPayload()).isNull();
        assertThat(mqtt.lastWillQos()).isEqualTo(0);
        assertThat(mqtt.lastWillRetained()).isFalse();
    }
}
