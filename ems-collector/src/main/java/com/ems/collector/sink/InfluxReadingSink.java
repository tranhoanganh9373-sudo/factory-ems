package com.ems.collector.sink;

import com.ems.collector.buffer.BufferStore;
import com.ems.collector.poller.DeviceReading;
import com.ems.collector.poller.ReadingSink;
import com.ems.meter.entity.EnergyType;
import com.ems.meter.entity.Meter;
import com.ems.meter.observability.MeterMetrics;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.repository.MeterRepository;
import com.ems.timeseries.config.InfluxProperties;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 把一次 polling 的全部 register decoded values 一次性写一个 InfluxDB Point。
 *
 * <p>Measurement / tag-key / tag-value 来自 meters 表（{@link Meter#getInfluxMeasurement()} /
 * {@link Meter#getInfluxTagKey()} / {@link Meter#getInfluxTagValue()}）—— 与 mock-data CLI
 * 用同一份 schema，下游 看板 / 报表 / 分摊 完全无感知地切换数据源。
 *
 * <p>多 field 一条 Point：每个 register 的 {@code tsField} 是 InfluxDB field key，BigDecimal
 * 写成 double（Influx 没原生 BigDecimal）；coil/discrete bool 直接写 boolean field。
 *
 * <p>{@link WriteApiBlocking}：同步写。collector 速率 << 1000 写/秒，无需 batching。
 *
 * <p>该 Bean 自动覆盖 {@link com.ems.collector.config.CollectorAutoConfiguration} 里的
 * {@code defaultReadingSink}（@ConditionalOnMissingBean）—— 一旦 InfluxDBClient 在 context 里，
 * 这条路径就生效。InfluxDBClient 由 ems-timeseries 的 InfluxConfig 提供。
 */
@Component
@ConditionalOnBean(InfluxDBClient.class)
public class InfluxReadingSink implements ReadingSink {

    private static final Logger log = LoggerFactory.getLogger(InfluxReadingSink.class);

    private final InfluxProperties influxProps;
    private final MeterRepository meters;
    private final WriteApiBlocking writeApi;
    private final BufferStore buffer;
    private final EnergyTypeRepository energyTypes;
    private final MeterMetrics meterMetrics;

    @Autowired
    public InfluxReadingSink(InfluxDBClient client, InfluxProperties influxProps,
                             MeterRepository meters, BufferStore buffer,
                             EnergyTypeRepository energyTypes, MeterMetrics meterMetrics) {
        this.influxProps = influxProps;
        this.meters = meters;
        this.writeApi = client.getWriteApiBlocking();
        this.buffer = buffer;
        this.energyTypes = energyTypes;
        this.meterMetrics = meterMetrics == null ? MeterMetrics.NOOP : meterMetrics;
    }

    @Override
    public void accept(DeviceReading reading) {
        Meter meter = meters.findByCode(reading.meterCode()).orElse(null);
        if (meter == null) {
            // 启动时已校验过 meter-code 存在；这里走到说明运行中被人删了
            log.warn("meter '{}' not found at write time (was deleted at runtime?); dropping reading from device {}",
                    reading.meterCode(), reading.deviceId());
            meterMetrics.incrementDropped("other");
            return;
        }
        Point p = buildPoint(reading, meter);
        try {
            writeApi.writePoint(influxProps.getBucket(), influxProps.getOrg(), p);
            meterMetrics.incrementInsert(resolveEnergyTypeCode(meter));
        } catch (Exception e) {
            log.warn("Influx write failed for device {} ({} fields): {} — buffering",
                    reading.deviceId(),
                    reading.numericFields().size() + reading.booleanFields().size(),
                    e.toString());
            // 失败 → 落 buffer，由 BufferFlushScheduler 后台补传
            if (buffer != null) {
                try {
                    buffer.enqueue(reading);
                } catch (Exception bufEx) {
                    log.error("buffer enqueue failed too — reading lost: {}", bufEx.toString());
                }
            }
        }
    }

    /**
     * Flush 路径：BufferFlushScheduler 调用，对一批 buffered reading 重试写 InfluxDB；
     * 写成功返回 true 让 caller markSent；写失败返 false 保留在 buffer。
     */
    public boolean flushOne(DeviceReading reading) {
        Meter meter = meters.findByCode(reading.meterCode()).orElse(null);
        if (meter == null) {
            // 老 reading 的 meter 没了；直接 markSent 让 buffer 清掉
            meterMetrics.incrementDropped("other");
            return true;
        }
        Point p = buildPoint(reading, meter);
        try {
            writeApi.writePoint(influxProps.getBucket(), influxProps.getOrg(), p);
            meterMetrics.incrementInsert(resolveEnergyTypeCode(meter));
            return true;
        } catch (Exception e) {
            // follow-up #1: 写失败必须落 log，否则 buffer 持续积累 / 磁盘满 / 数据丢失全程零信号
            log.warn("flush retry failed device={} meter={}: {}",
                reading.deviceId(), reading.meterCode(), e.toString());
            return false;
        }
    }

    /**
     * Meter.energyTypeId → EnergyType.code 解析。
     * <p>v1 不缓存：energy_types 表只有几行，repository 调用 ~1ms 量级，影响可以忽略；
     * 真正成为热点时再加 {@code @Cacheable("energyTypeCodes")} 即可。
     */
    private String resolveEnergyTypeCode(Meter meter) {
        if (meter == null || meter.getEnergyTypeId() == null) return "other";
        return energyTypes.findById(meter.getEnergyTypeId())
                .map(EnergyType::getCode)
                .orElse("other");
    }

    /** package-visible for unit tests. */
    static Point buildPoint(DeviceReading reading, Meter meter) {
        Point p = Point.measurement(meter.getInfluxMeasurement())
                .addTag(meter.getInfluxTagKey(), meter.getInfluxTagValue())
                .time(reading.timestamp(), WritePrecision.MS);
        for (Map.Entry<String, BigDecimal> e : reading.numericFields().entrySet()) {
            p.addField(e.getKey(), e.getValue().doubleValue());
        }
        for (Map.Entry<String, Boolean> e : reading.booleanFields().entrySet()) {
            p.addField(e.getKey(), e.getValue());
        }
        return p;
    }
}
