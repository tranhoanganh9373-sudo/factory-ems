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
 * <p>映射规则：通过 (sample.channelId, sample.pointKey) 反查 meter，
 * 用 meter.influxMeasurement / influxTagKey / influxTagValue 构建 Point。
 *
 * <p>约定：channel 配置中 {@code points[].key} 应等于对应 meter 的 {@code code}。
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

    public InfluxSampleWriter(InfluxDBClient client,
                              InfluxProperties influxProps,
                              MeterRepository meters) {
        this.influxProps = influxProps;
        this.meters = meters;
        this.writeApi = client.getWriteApiBlocking();
    }

    @Override
    public void write(Sample sample) {
        if (sample == null || sample.channelId() == null || sample.pointKey() == null) {
            return;
        }

        Optional<Meter> meterOpt = meters.findByChannelIdAndCode(
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

            // pointKey 即 InfluxDB field key
            String fieldKey = sample.pointKey();
            Object value = sample.value();
            if (value == null) {
                log.debug("null value for sample channel={} point={}; skipping",
                    sample.channelId(), sample.pointKey());
                return;
            }
            if (value instanceof Number n) {
                point.addField(fieldKey, n.doubleValue());
            } else if (value instanceof Boolean b) {
                point.addField(fieldKey, b);
            } else {
                point.addField(fieldKey, value.toString());
            }

            writeApi.writePoint(influxProps.getBucket(), influxProps.getOrg(), point);
        } catch (Exception e) {
            // 显式 swallow：避免单条 sample 失败导致 transport 调度链中断
            log.warn("influx write failed channel={} point={}: {}",
                sample.channelId(), sample.pointKey(), e.toString());
        }
    }
}
