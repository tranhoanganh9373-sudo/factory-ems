package com.ems.collector.transport;

/**
 * Transport → Service 的样本回调。
 *
 * <p>实现必须线程安全；Transport 内部多线程 / 订阅回调都会调用此方法。
 */
@FunctionalInterface
public interface SampleSink {
    void accept(Sample sample);
}
