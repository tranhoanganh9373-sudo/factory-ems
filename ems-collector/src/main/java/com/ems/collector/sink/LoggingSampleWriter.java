package com.ems.collector.sink;

import com.ems.collector.transport.Sample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * SampleWriter fallback 实装：写日志 + 内存 ring buffer。
 *
 * <p>每个 channel 保留最近 {@value #BUFFER_PER_CHANNEL} 条 sample，供诊断/调试。
 *
 * <p>{@link InfluxSampleWriter}（@ConditionalOnBean(InfluxDBClient)）优先注册；
 * 本类通过 {@link ConditionalOnMissingBean} 仅在 InfluxDB 不可用时作为 fallback，
 * 保证 collector 在没有时序库的环境（开发 / 单元测试 / Influx 离线）也能跑通。
 *
 * <p>线程安全：底层 {@link ConcurrentHashMap} + {@link ConcurrentLinkedDeque}；
 * size 维护用 deque 自身的 size + 头尾原子操作（read 端容忍偶发 over-trim）。
 */
@Component
@ConditionalOnMissingBean(SampleWriter.class)
public class LoggingSampleWriter implements SampleWriter {

    private static final Logger log = LoggerFactory.getLogger(LoggingSampleWriter.class);
    static final int BUFFER_PER_CHANNEL = 100;

    private final ConcurrentHashMap<Long, Deque<Sample>> buffers = new ConcurrentHashMap<>();

    @Override
    public void write(Sample sample) {
        if (sample == null || sample.channelId() == null) {
            return;
        }
        var deque = buffers.computeIfAbsent(sample.channelId(), k -> new ConcurrentLinkedDeque<>());
        deque.addLast(sample);
        // 限制 deque 大小（容忍并发 over-trim）
        while (deque.size() > BUFFER_PER_CHANNEL) {
            deque.pollFirst();
        }
        log.debug("sample channel={} point={} value={} quality={}",
            sample.channelId(), sample.pointKey(), sample.value(), sample.quality());
    }

    /** 返回 channel 最近 limit 条样本（最新在前），不存在则返回空列表。 */
    public List<Sample> getRecentSamples(Long channelId, int limit) {
        var deque = buffers.get(channelId);
        if (deque == null) return List.of();
        var snapshot = new ArrayList<>(deque);
        Collections.reverse(snapshot);
        return snapshot.size() <= limit ? snapshot : snapshot.subList(0, limit);
    }
}
