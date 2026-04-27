package com.ems.collector.transport;

import com.ems.collector.config.DeviceConfig;
import com.ems.collector.config.Protocol;
import org.springframework.stereotype.Component;

/** 真实部署用的 factory：TCP → {@link TcpModbusMaster}；RTU 在 Plan 1.5.2 实现。 */
@Component
public class DefaultModbusMasterFactory implements ModbusMasterFactory {

    @Override
    public ModbusMaster create(DeviceConfig device) {
        if (device.protocol() == Protocol.TCP) {
            return new TcpModbusMaster(device.host(), device.port(), device.timeoutMs());
        }
        throw new IllegalStateException(
                "Protocol " + device.protocol() + " not supported in Plan 1.5.1 (only TCP)");
    }
}
