package com.ems.collector.runtime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存中的 channel 运行状态注册表（per-collector singleton）。
 *
 * <p>每 channel 一个 {@link MutableState}：连接状态 + 时间戳 + 24h 计数器 + 100 次滑动延迟窗口。
 *
 * <p>所有 mutator 由 {@link com.ems.collector.transport.SampleSink} 回调线程调用，
 * 故采用 ConcurrentHashMap + per-state 字段 volatile 即可（非强一致快照，监控用）。
 */
@Component
public class ChannelStateRegistry {

    private static final int LATENCY_WINDOW = 100;
    private static final int ERROR_MSG_MAX = 200;

    private final Map<Long, MutableState> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public ChannelStateRegistry(@Qualifier("collectorClock") Clock clock) {
        this.clock = clock;
    }

    public void register(Long id, String protocol) {
        states.put(id, new MutableState(protocol, clock));
    }

    public void unregister(Long id) {
        states.remove(id);
    }

    public void recordSuccess(Long id, long latencyMs) {
        MutableState s = states.get(id);
        if (s == null) return;
        s.connState = ConnectionState.CONNECTED;
        s.lastSuccessAt = clock.instant();
        s.counter.recordSuccess();
        s.recordLatency(latencyMs);
    }

    public void recordFailure(Long id, String error) {
        MutableState s = states.get(id);
        if (s == null) return;
        s.connState = ConnectionState.DISCONNECTED;
        s.lastFailureAt = clock.instant();
        s.lastErrorMessage = truncate(error);
        s.counter.recordFailure();
    }

    public void setState(Long id, ConnectionState state) {
        MutableState s = states.get(id);
        if (s != null) s.connState = state;
    }

    public ChannelRuntimeState snapshot(Long id) {
        MutableState s = states.get(id);
        return s == null ? null : s.toRecord(id);
    }

    public Collection<ChannelRuntimeState> snapshotAll() {
        return states.entrySet().stream()
                .map(e -> e.getValue().toRecord(e.getKey()))
                .toList();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > ERROR_MSG_MAX ? s.substring(0, ERROR_MSG_MAX) : s;
    }

    private static final class MutableState {
        final String protocol;
        final HourlyCounter counter;
        volatile ConnectionState connState = ConnectionState.CONNECTING;
        volatile Instant lastConnectAt;
        volatile Instant lastSuccessAt;
        volatile Instant lastFailureAt;
        volatile String lastErrorMessage;
        final long[] latencyWindow = new long[LATENCY_WINDOW];
        int latencyIdx = 0;
        int latencyCount = 0;
        final Map<String, Object> protocolMeta = new HashMap<>();

        MutableState(String protocol, Clock clock) {
            this.protocol = protocol;
            this.counter = new HourlyCounter(clock);
            this.lastConnectAt = clock.instant();
        }

        synchronized void recordLatency(long ms) {
            latencyWindow[latencyIdx] = ms;
            latencyIdx = (latencyIdx + 1) % LATENCY_WINDOW;
            if (latencyCount < LATENCY_WINDOW) latencyCount++;
        }

        synchronized long avgLatency() {
            if (latencyCount == 0) return 0L;
            long sum = 0;
            for (int i = 0; i < latencyCount; i++) sum += latencyWindow[i];
            return sum / latencyCount;
        }

        ChannelRuntimeState toRecord(Long id) {
            return new ChannelRuntimeState(
                    id, protocol, connState,
                    lastConnectAt, lastSuccessAt, lastFailureAt, lastErrorMessage,
                    counter.total24h(true), counter.total24h(false),
                    avgLatency(), Map.copyOf(protocolMeta));
        }
    }
}
