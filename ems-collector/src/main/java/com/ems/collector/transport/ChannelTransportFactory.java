package com.ems.collector.transport;

import com.ems.collector.cert.OpcUaCertificateStore;
import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.secret.SecretResolver;
import com.ems.collector.transport.impl.ModbusRtuAdapterTransport;
import com.ems.collector.transport.impl.ModbusTcpAdapterTransport;
import com.ems.collector.transport.impl.MqttTransport;
import com.ems.collector.transport.impl.OpcUaTransport;
import com.ems.collector.transport.impl.VirtualTransport;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * protocol 字符串 → 新建一个 {@link Transport} 实例。
 *
 * <p>每次 {@link #create(String)} 返回新对象，channel 间互不共享。
 */
@Component
public class ChannelTransportFactory {

    private final SecretResolver secretResolver;
    private final OpcUaCertificateStore certStore;
    private final ApplicationEventPublisher eventPublisher;
    private final ChannelStateRegistry stateRegistry;

    public ChannelTransportFactory(SecretResolver secretResolver, OpcUaCertificateStore certStore,
                                   ApplicationEventPublisher eventPublisher,
                                   ChannelStateRegistry stateRegistry) {
        this.secretResolver = secretResolver;
        this.certStore = certStore;
        this.eventPublisher = eventPublisher;
        this.stateRegistry = stateRegistry;
    }

    public Transport create(String protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol must not be null");
        }
        return switch (protocol) {
            case "MODBUS_TCP" -> new ModbusTcpAdapterTransport(stateRegistry);
            case "MODBUS_RTU" -> new ModbusRtuAdapterTransport(stateRegistry);
            case "OPC_UA"     -> new OpcUaTransport(secretResolver, certStore, eventPublisher);
            case "MQTT"       -> new MqttTransport(secretResolver);
            case "VIRTUAL"    -> new VirtualTransport();
            default -> throw new IllegalArgumentException("unknown protocol: " + protocol);
        };
    }
}
