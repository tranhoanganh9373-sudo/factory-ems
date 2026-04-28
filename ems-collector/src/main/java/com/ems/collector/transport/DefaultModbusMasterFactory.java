package com.ems.collector.transport;

import com.ems.collector.config.DeviceConfig;
import com.ems.collector.config.Protocol;
import org.springframework.stereotype.Component;

/** 真实部署用的 factory：TCP → {@link TcpModbusMaster}；RTU → {@link RtuModbusMaster}（Plan 1.5.2）。 */
@Component
public class DefaultModbusMasterFactory implements ModbusMasterFactory {

    @Override
    public ModbusMaster create(DeviceConfig device) {
        return switch (device.protocol()) {
            case TCP -> new TcpModbusMaster(device.host(), device.port(), device.timeoutMs());
            case RTU -> new RtuModbusMaster(device);
        };
    }
}
