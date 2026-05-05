package com.ems.collector.sink;

import com.ems.collector.transport.Sample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * SampleWriter fallback 实装：写日志，并把 sample 喂给 {@link DiagnosticRingBuffer}。
 *
 * <p>{@link InfluxSampleWriter}（@ConditionalOnBean(InfluxDBClient)）优先注册；
 * 本类通过 {@link ConditionalOnMissingBean} 仅在 InfluxDB 不可用时作为 fallback，
 * 保证 collector 在没有时序库的环境（开发 / 单元测试 / Influx 离线）也能跑通。
 *
 * <p>诊断 ring buffer 已抽出到 {@link DiagnosticRingBuffer}（始终注册），
 * 两个 writer 都喂它，{@code ChannelDiagnosticsService} 从那里读，
 * 这样诊断 drawer 在生产环境也能看到样本。
 */
@Component
@ConditionalOnMissingBean(SampleWriter.class)
public class LoggingSampleWriter implements SampleWriter {

    private static final Logger log = LoggerFactory.getLogger(LoggingSampleWriter.class);

    private final DiagnosticRingBuffer ring;

    public LoggingSampleWriter(DiagnosticRingBuffer ring) {
        this.ring = ring;
    }

    @Override
    public void write(Sample sample) {
        if (sample == null || sample.channelId() == null) {
            return;
        }
        ring.record(sample);
        log.debug("sample channel={} point={} value={} quality={}",
            sample.channelId(), sample.pointKey(), sample.value(), sample.quality());
    }
}
