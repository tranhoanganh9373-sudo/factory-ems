package com.ems.collector.sink;

import com.ems.collector.transport.Sample;

/**
 * 把采集到的 {@link Sample} 持久化到下游（InfluxDB / Buffer / 内存 ring）。
 *
 * <p>Phase 2 仅声明接口，实现类由 Phase 7 整合既有 {@code InfluxReadingSink}。
 * 测试期用 {@code mock(SampleWriter.class)} 替代。
 *
 * <p>实现必须线程安全 — 多个 channel 的 transport 会并发调用 {@link #write(Sample)}。
 */
public interface SampleWriter {
    void write(Sample sample);
}
