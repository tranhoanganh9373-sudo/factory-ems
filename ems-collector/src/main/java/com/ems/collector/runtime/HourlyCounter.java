package com.ems.collector.runtime;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 24 小时环形计数器 — UTC 小时桶 0..23。
 *
 * <p>每次 record 时检查当前 UTC 小时，如果跨小时则把 (上一槽, 新槽] 区间内的旧数据清零，
 * 然后在新槽累计。{@link #total24h(boolean)} 简单求和所有 24 个槽（最多回看 23 小时）。
 *
 * <p>线程安全：record 操作用 ReentrantLock 保护 roll-over 区间清零；
 * total24h 不加锁（弱一致即可，监控用途）。
 */
public class HourlyCounter {

    private static final int BUCKETS = 24;

    private final long[] success = new long[BUCKETS];
    private final long[] failure = new long[BUCKETS];
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile int currentSlot;

    public HourlyCounter(Clock clock) {
        this.clock = clock;
        this.currentSlot = computeSlot();
    }

    private int computeSlot() {
        return clock.instant().atZone(ZoneOffset.UTC).getHour();
    }

    private void rollIfNeeded() {
        int slot = computeSlot();
        if (slot == currentSlot) return;
        lock.lock();
        try {
            if (slot != currentSlot) {
                // 把 (currentSlot, slot] 区间所有桶清零（包含 slot）
                int i = (currentSlot + 1) % BUCKETS;
                while (true) {
                    success[i] = 0;
                    failure[i] = 0;
                    if (i == slot) break;
                    i = (i + 1) % BUCKETS;
                }
                currentSlot = slot;
            }
        } finally {
            lock.unlock();
        }
    }

    public void recordSuccess() {
        rollIfNeeded();
        success[currentSlot]++;
    }

    public void recordFailure() {
        rollIfNeeded();
        failure[currentSlot]++;
    }

    public long total24h(boolean ok) {
        return Arrays.stream(ok ? success : failure).sum();
    }
}
