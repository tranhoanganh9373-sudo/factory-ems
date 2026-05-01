package com.ems.collector.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Duration;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "protocol")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ModbusTcpConfig.class,  name = "MODBUS_TCP"),
    @JsonSubTypes.Type(value = ModbusRtuConfig.class,  name = "MODBUS_RTU"),
    @JsonSubTypes.Type(value = OpcUaConfig.class,      name = "OPC_UA"),
    @JsonSubTypes.Type(value = MqttConfig.class,       name = "MQTT"),
    @JsonSubTypes.Type(value = VirtualConfig.class,    name = "VIRTUAL")
})
public sealed interface ChannelConfig
    permits ModbusTcpConfig, ModbusRtuConfig, OpcUaConfig, MqttConfig, VirtualConfig {

    String protocol();

    /**
     * 轮询间隔；PULL 协议（Modbus / OPC UA Read / VIRTUAL）必须返回非空值，
     * PUSH 协议（MQTT、OPC UA 仅 Subscribe 模式）返回 {@code null}。
     *
     * 调用方必须通过 {@code instanceof} 模式或类型分发判断是否需要轮询。
     * 不要直接调用 {@code cfg.pollInterval().toMillis()} 而不做空判断。
     */
    Duration pollInterval();

    List<? extends PointConfig> points();
}
