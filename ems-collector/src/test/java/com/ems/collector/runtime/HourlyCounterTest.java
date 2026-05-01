package com.ems.collector.runtime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HourlyCounterTest {

    @Test
    void countsWithinSameHour() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-30T10:30:00Z"), ZoneOffset.UTC);
        HourlyCounter c = new HourlyCounter(clock);

        c.recordSuccess();
        c.recordSuccess();
        c.recordFailure();

        assertThat(c.total24h(true)).isEqualTo(2);
        assertThat(c.total24h(false)).isEqualTo(1);
    }

    @Test
    void rollsOverWhenHourChanges() {
        AtomicReference<Instant> ts = new AtomicReference<>(Instant.parse("2026-04-30T10:30:00Z"));
        Clock clock = mutableClock(ts);
        HourlyCounter c = new HourlyCounter(clock);

        c.recordSuccess();                                       // success[10] = 1
        ts.set(Instant.parse("2026-04-30T11:30:00Z"));
        c.recordSuccess();                                       // roll → success[11] = 1, success[10] 保留

        assertThat(c.total24h(true)).isEqualTo(2);
    }

    @Test
    void clearsOldBucketWhenRollingThroughIt() {
        // 跨多个小时让旧桶被清零：start 在 10，跳到 14，期间 11/12/13 不会有数据
        // 然后再跳到 11，11 桶应该被清零（不携带 24h 前的数据）
        AtomicReference<Instant> ts = new AtomicReference<>(Instant.parse("2026-04-30T11:00:00Z"));
        Clock clock = mutableClock(ts);
        HourlyCounter c = new HourlyCounter(clock);

        c.recordSuccess();                          // success[11] = 1
        c.recordSuccess();                          // success[11] = 2
        // 跳到 14 — roll-over 清零 12/13/14
        ts.set(Instant.parse("2026-04-30T14:00:00Z"));
        c.recordSuccess();                          // success[14] = 1, success[11] 保留 = 2
        assertThat(c.total24h(true)).isEqualTo(3);

        // 再跳到 11（次日）— roll-over 清零 15..10..11 区间，包括 11
        ts.set(Instant.parse("2026-05-01T11:00:00Z"));
        c.recordSuccess();                          // success[11] = 1（旧的 2 被清零）
        // 此时活跃桶：success[14] 保留 = 1（昨天 14:00），success[11] = 1（今天）
        assertThat(c.total24h(true)).isEqualTo(2);
    }

    @Test
    void mixedSuccessAndFailureAcrossHours() {
        AtomicReference<Instant> ts = new AtomicReference<>(Instant.parse("2026-04-30T10:00:00Z"));
        Clock clock = mutableClock(ts);
        HourlyCounter c = new HourlyCounter(clock);

        c.recordSuccess();
        c.recordFailure();
        ts.set(Instant.parse("2026-04-30T11:00:00Z"));
        c.recordSuccess();
        c.recordFailure();
        c.recordFailure();

        assertThat(c.total24h(true)).isEqualTo(2);
        assertThat(c.total24h(false)).isEqualTo(3);
    }

    private static Clock mutableClock(AtomicReference<Instant> ts) {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return ts.get(); }
        };
    }
}
