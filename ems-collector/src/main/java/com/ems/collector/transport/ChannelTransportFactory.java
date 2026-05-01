package com.ems.collector.transport;

import com.ems.collector.secret.SecretResolver;
import com.ems.collector.transport.impl.ModbusRtuAdapterTransport;
import com.ems.collector.transport.impl.ModbusTcpAdapterTransport;
import com.ems.collector.transport.impl.MqttTransport;
import com.ems.collector.transport.impl.OpcUaTransport;
import com.ems.collector.transport.impl.VirtualTransport;
import org.springframework.stereotype.Component;

/**
 * protocol 字符串 → 新建一个 {@link Transport} 实例。
 *
 * <p>每次 {@link #create(String)} 返回新对象，channel 间互不共享。
 */
@Component
public class ChannelTransportFactory {

    private final SecretResolver secretResolver;

    public ChannelTransportFactory(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    public Transport create(String protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol must not be null");
        }
        return switch (protocol) {
            case "MODBUS_TCP" -> new ModbusTcpAdapterTransport();
            case "MODBUS_RTU" -> new ModbusRtuAdapterTransport();
            case "OPC_UA"     -> new OpcUaTransport(secretResolver);
            case "MQTT"       -> new MqttTransport(secretResolver);
            case "VIRTUAL"    -> new VirtualTransport();
            default -> throw new IllegalArgumentException("unknown protocol: " + protocol);
        };
    }
}
