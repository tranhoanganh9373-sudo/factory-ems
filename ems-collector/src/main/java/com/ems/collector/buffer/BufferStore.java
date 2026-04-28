package com.ems.collector.buffer;

import com.ems.collector.poller.DeviceReading;

import java.util.List;

/**
 * 失败 reading 的持久化队列。Sink 写 InfluxDB 失败 → 落 buffer；后台 task 周期补传。
 *
 * <p>实现要求：
 * <ul>
 *   <li>FIFO（按落库顺序）</li>
 *   <li>幂等 ack（{@link #markSent(List)} 同 id 多次调用不出错）</li>
 *   <li>超出 {@link BufferProperties#maxRowsPerDevice()} 时丢最旧</li>
 *   <li>超过 {@link BufferProperties#ttlDays()} 自动清理（vacuum 由实现决定时机）</li>
 * </ul>
 */
public interface BufferStore {

    /** 入库。返回新 row id（用于后续 markSent）。 */
    long enqueue(DeviceReading reading);

    /** 取最早的 N 条未发 reading（{@code sent=0}）。 */
    List<BufferEntry> peekUnsent(int limit);

    /** 标 entries 为 sent=1。幂等。 */
    void markSent(List<Long> ids);

    /** 当前未发 entries 数（用于监控）。 */
    long unsentCount();

    /** 超 TTL / 超 maxRowsPerDevice 时清理。 */
    void vacuum();

    /** Buffer entry 投影。 */
    record BufferEntry(long id, String deviceId, String meterCode, DeviceReading reading) {}
}
