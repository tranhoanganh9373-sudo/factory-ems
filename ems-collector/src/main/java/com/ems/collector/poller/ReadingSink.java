package com.ems.collector.poller;

/**
 * 把 {@link DeviceReading} 送出 collector 边界的下游。Phase G 用 InfluxDB 实现。
 *
 * <p>实现方必须线程安全 —— 多个 DevicePoller 可能同时调用。
 *
 * <p>实现方应吞掉自己的写错误（落 audit 或重试），不应抛出让 poller 误判周期失败。
 */
@FunctionalInterface
public interface ReadingSink {
    void accept(DeviceReading reading);
}
