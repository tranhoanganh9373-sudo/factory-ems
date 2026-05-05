package com.ems.collector.sink;

import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 每 channel 保留最近 {@value #BUFFER_PER_CHANNEL} 条 sample 的内存 ring，给诊断 UI 用。
 *
 * <p>始终注册（不依赖任何 SampleWriter 实现）：{@link InfluxSampleWriter} /
 * {@link LoggingSampleWriter} 在 write() 时都把 sample 喂进来。这样诊断 drawer 在
 * 时序库可用与否两种部署下行为一致——开发期能看到，生产期也能看到。
 *
 * <p>线程安全：底层 {@link ConcurrentHashMap} + {@link ConcurrentLinkedDeque}；
 * size 维护用 deque 自身的 size + 头尾原子操作（read 端容忍偶发 over-trim）。
 */
@Component
public class DiagnosticRingBuffer {

    static final int BUFFER_PER_CHANNEL = 100;

    private final ConcurrentHashMap<Long, Deque<Sample>> buffers = new ConcurrentHashMap<>();

    /** 把 sample 喂进对应 channel 的环形缓冲；null / 无 channelId 直接忽略。 */
    public void record(Sample sample) {
        if (sample == null || sample.channelId() == null) return;
        Deque<Sample> deque =
            buffers.computeIfAbsent(sample.channelId(), k -> new ConcurrentLinkedDeque<>());
        deque.addLast(sample);
        while (deque.size() > BUFFER_PER_CHANNEL) {
            deque.pollFirst();
        }
    }

    /** 返回 channel 最近 limit 条 sample（最新在前），channel 不存在返回空列表。 */
    public List<Sample> getRecentSamples(Long channelId, int limit) {
        Deque<Sample> deque = buffers.get(channelId);
        if (deque == null) return List.of();
        ArrayList<Sample> snapshot = new ArrayList<>(deque);
        Collections.reverse(snapshot);
        return snapshot.size() <= limit ? snapshot : snapshot.subList(0, limit);
    }

    /**
     * 返回指定 (channelId, pointKey) 在缓冲区中最近一条 GOOD 样本的时间，没有则 null。
     * 用于"看到样本才算在线"语义：alarm health summary 据此判断 meter 是否 online。
     *
     * <p>BAD/UNCERTAIN 样本不算——决定健康度的是有效读数，而不是"transport 还在跑"。
     */
    public Instant lastGoodSampleAt(Long channelId, String pointKey) {
        if (channelId == null || pointKey == null) return null;
        Deque<Sample> deque = buffers.get(channelId);
        if (deque == null) return null;
        Instant best = null;
        for (Sample s : deque) {
            if (s.quality() != Quality.GOOD) continue;
            if (!pointKey.equals(s.pointKey())) continue;
            Instant ts = s.timestamp();
            if (ts == null) continue;
            if (best == null || ts.isAfter(best)) best = ts;
        }
        return best;
    }
}
