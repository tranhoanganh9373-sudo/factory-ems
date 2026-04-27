package com.ems.collector.transport;

import com.ems.collector.config.DeviceConfig;

/**
 * 让 {@link com.ems.collector.service.CollectorService} 创建 master 时不直接 new，便于测试注入 fake。
 */
@FunctionalInterface
public interface ModbusMasterFactory {
    ModbusMaster create(DeviceConfig device);
}
