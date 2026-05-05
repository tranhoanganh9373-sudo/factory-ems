package com.ems.collector.sink;

import com.ems.collector.transport.Sample;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import com.ems.timeseries.config.InfluxProperties;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * SampleWriter 实装：把 collector 采集的 {@link Sample} 桥接到 InfluxDB。
 *
 * <p>映射规则：通过 (sample.channelId, sample.pointKey) 反查 meter（按 meter 的
 * {@code channel_point_key} 列匹配，V2.3.2 起与 {@code code} 解耦），
 * 用 meter.influxMeasurement / influxTagKey / influxTagValue 构建 Point。
 *
 * <p>约定：channel 配置中 {@code points[].key} 等于对应 meter 的 {@code channelPointKey}；
 * meter.code 是纯业务标识，不再参与采集器侧路由。
 *
 * <p>{@link ConditionalOnBean}：仅在 {@link InfluxDBClient} 可用时启用。
 * 与 {@link LoggingSampleWriter} 通过 {@code @ConditionalOnMissingBean(SampleWriter.class)}
 * 互斥 —— InfluxDB 优先；InfluxDB 不可用时 fallback 到 LoggingSampleWriter。
 */
@Component
@ConditionalOnBean(InfluxDBClient.class)
public class InfluxSampleWriter implements SampleWriter {

    private static final Logger log = LoggerFactory.getLogger(InfluxSampleWriter.class);

    private final InfluxProperties influxProps;
    private final MeterRepository meters;
    private final WriteApiBlocking writeApi;
    private final DiagnosticRingBuffer ring;

    public InfluxSampleWriter(InfluxDBClient client,
                              InfluxProperties influxProps,
                              MeterRepository meters,
                              DiagnosticRingBuffer ring) {
        this.influxProps = influxProps;
        this.meters = meters;
        this.writeApi = client.getWriteApiBlocking();
        this.ring = ring;
    }

    @Override
    public void write(Sample sample) {
        if (sample == null || sample.channelId() == null || sample.pointKey() == null) {
            return;
        }
        // 诊断缓冲：无论后面 Influx 路径成功与否，都先喂一条进 ring，让 UI 看到采集动了
        ring.record(sample);

        Optional<Meter> meterOpt = meters.findByChannelIdAndChannelPointKey(
            sample.channelId(), sample.pointKey());
        if (meterOpt.isEmpty()) {
            // channel 未绑 / pointKey 不匹配任何 meter；channel 可能服务于多种用途，不报错
            log.debug("no meter for sample channel={} point={}; skipping",
                sample.channelId(), sample.pointKey());
            return;
        }
        Meter meter = meterOpt.get();

        try {
            Point point = Point.measurement(meter.getInfluxMeasurement())
                .addTag(meter.getInfluxTagKey(), meter.getInfluxTagValue())
                .time(sample.timestamp() != null ? sample.timestamp() : Instant.now(),
                    WritePrecision.MS);

            // 每个 meter 通过 meter_code tag 区分；field 固定写 "value" 以匹配
            // FluxQueryBuilder.sumOverRange() 等查询的硬编码 `_field == "value"`。
            // pointKey 是 collector 内部寻址 key，不应作为 schema 字段泄漏到时序库。
            Object value = sample.value();
            if (value == null) {
                log.debug("null value for sample channel={} point={}; skipping",
                    sample.channelId(), sample.pointKey());
                return;
            }
            if (value instanceof Number n) {
                point.addField("value", n.doubleValue());
            } else if (value instanceof Boolean b) {
                point.addField("value", b);
            } else {
                point.addField("value", value.toString());
            }

            writeApi.writePoint(influxProps.getBucket(), influxProps.getOrg(), point);
        } catch (Exception e) {
            // 显式 swallow：避免单条 sample 失败导致 transport 调度链中断
            log.warn("influx write failed channel={} point={}: {}",
                sample.channelId(), sample.pointKey(), e.toString());
        }
    }
}
