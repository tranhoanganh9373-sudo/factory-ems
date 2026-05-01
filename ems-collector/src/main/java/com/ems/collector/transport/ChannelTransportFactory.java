package com.ems.collector.transport;

import org.springframework.stereotype.Component;

/**
 * protocol 字符串 → 新建一个 {@link Transport} 实例。
 *
 * <p>每次 {@link #create(String)} 返回新对象，channel 间互不共享。
 */
@Component
public class ChannelTransportFactory {

    public Transport create(String protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol must not be null");
        }
        return switch (protocol) {
            case "MODBUS_TCP" -> new ModbusTcpAdapterTransport();
            case "MODBUS_RTU" -> new ModbusRtuAdapterTransport();
            case "OPC_UA"     -> new OpcUaTransport();
            case "MQTT"       -> new MqttTransport();
            case "VIRTUAL"    -> new VirtualTransport();
            default -> throw new IllegalArgumentException("unknown protocol: " + protocol);
        };
    }
}
