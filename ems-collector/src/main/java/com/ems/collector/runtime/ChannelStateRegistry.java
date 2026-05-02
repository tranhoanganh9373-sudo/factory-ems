package com.ems.collector.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存中的 channel 运行状态注册表（per-collector singleton）。
 *
 * <p>每 channel 一个 {@link MutableState}：连接状态 + 时间戳 + 24h 计数器 + 100 次滑动延迟窗口。
 *
 * <p>所有 mutator 由 {@link com.ems.collector.transport.SampleSink} 回调线程调用，
 * 故采用 ConcurrentHashMap + per-state 字段 volatile 即可（非强一致快照，监控用）。
 *
 * <p>连续失败到达 {@link #FAILURE_THRESHOLD} 时发布一次 {@link ChannelFailureEvent}；
 * 故障期间收到首次成功时发布 {@link ChannelRecoveredEvent}。每 channel 各发一次，幂等。
 */
@Component
public class ChannelStateRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChannelStateRegistry.class);

    private static final int LATENCY_WINDOW = 100;
    private static final int ERROR_MSG_MAX = 200;

    /** 连续失败达到该阈值时发布 {@link ChannelFailureEvent}。 */
    static final int FAILURE_THRESHOLD = 5;

    /**
     * connState 转换的 hysteresis 阈值，避免 modbus 间歇通讯时 UI 在 CONNECTED/DISCONNECTED
     * 之间高频抖动。DISCONNECTED → CONNECTED 需连续达标次数的成功；CONNECTED → DISCONNECTED
     * 需连续达标次数的失败。CONNECTING（初始/重连）允许首次失败即切 DISCONNECTED，体现
     * "正在判断连接是否成功"的语义。
     */
    static final int STATE_FLIP_THRESHOLD = 3;

    private final Map<Long, MutableState> states = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    public ChannelStateRegistry(@Qualifier("collectorClock") Clock clock,
                                ApplicationEventPublisher publisher) {
        this.clock = clock;
        this.publisher = publisher;
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
        s.lastSuccessAt = clock.instant();
        s.recordLatency(latencyMs);

        boolean wasFault;
        boolean shouldConnect;
        boolean countAsSuccess;
        synchronized (s) {
            wasFault = s.faultRaised;
            s.consecutiveFailures = 0;
            s.faultRaised = false;
            s.consecutiveSuccesses++;
            shouldConnect = s.connState != ConnectionState.CONNECTED
                    && s.consecutiveSuccesses >= STATE_FLIP_THRESHOLD;
            if (shouldConnect) {
                s.connState = ConnectionState.CONNECTED;
            }
            // 24h 成功率口径：以稳定 connState 为准。仍处于 DISCONNECTED/CONNECTING 时
            // 即使本次 sample GOOD 也不视为"通道可用"，避免间歇通讯期间成功率虚高。
            countAsSuccess = s.connState == ConnectionState.CONNECTED;
        }
        if (countAsSuccess) {
            s.counter.recordSuccess();
        } else {
            s.counter.recordFailure();
        }
        if (wasFault) {
            safePublish(new ChannelRecoveredEvent(id, clock.instant()));
        }
    }

    public void recordFailure(Long id, String error) {
        MutableState s = states.get(id);
        if (s == null) return;
        s.lastFailureAt = clock.instant();
        s.lastErrorMessage = truncate(error);
        s.counter.recordFailure();

        boolean transitioned;
        int consecutiveSnapshot;
        String protocolSnapshot;
        String errorSnapshot;
        boolean shouldDisconnect;
        synchronized (s) {
            s.consecutiveFailures++;
            s.consecutiveSuccesses = 0;
            transitioned = s.consecutiveFailures == FAILURE_THRESHOLD && !s.faultRaised;
            if (transitioned) s.faultRaised = true;
            consecutiveSnapshot = s.consecutiveFailures;
            protocolSnapshot = s.protocol;
            errorSnapshot = s.lastErrorMessage;
            shouldDisconnect = s.connState != ConnectionState.DISCONNECTED
                    && (s.connState == ConnectionState.CONNECTING
                        || s.consecutiveFailures >= STATE_FLIP_THRESHOLD);
        }
        if (shouldDisconnect) {
            s.connState = ConnectionState.DISCONNECTED;
        }
        if (transitioned) {
            safePublish(new ChannelFailureEvent(
                    id, protocolSnapshot, errorSnapshot, consecutiveSnapshot, clock.instant()));
        }
    }

    /**
     * 包裹 {@link ApplicationEventPublisher#publishEvent}：listener 抛出异常不能传回采集线程。
     * Spring 同步事件分发会把 listener 异常重新抛回 publisher，故此处必须 catch。
     */
    private void safePublish(Object event) {
        try {
            publisher.publishEvent(event);
        } catch (Throwable t) {
            log.warn("publishEvent failed for {}: {}", event.getClass().getSimpleName(), t.toString());
        }
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
        /** 连续失败计数 — 由 synchronized(this) 保护。 */
        int consecutiveFailures = 0;
        /** 连续成功计数 — 用于 connState hysteresis，由 synchronized(this) 保护。 */
        int consecutiveSuccesses = 0;
        /** 当前是否已发布 fault 事件 — 由 synchronized(this) 保护，确保只发一次。 */
        boolean faultRaised = false;
        final Map<String, Object> protocolMeta = new ConcurrentHashMap<>();

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
