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
    Duration pollInterval();
    List<? extends PointConfig> points();
}
