package com.ems.mockdata.timeseries;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteOptions;
import com.influxdb.client.write.Point;
import com.influxdb.client.domain.WritePrecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes raw 1-minute points to InfluxDB in batches of 5000.
 * Skip entirely when --no-influx=true is passed.
 */
@Component
public class InfluxBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(InfluxBatchWriter.class);
    private static final int BATCH_SIZE = 5000;

    private final InfluxDBClient influxClient;

    public InfluxBatchWriter(InfluxDBClient influxClient) {
        this.influxClient = influxClient;
    }

    /**
     * Record of a single minute reading.
     */
    public record MinutePoint(String measurement, String tagKey, String tagValue,
                              Instant ts, double value) {}

    private final List<MinutePoint> buffer = new ArrayList<>(BATCH_SIZE + 1);

    public void add(MinutePoint mp) {
        buffer.add(mp);
        if (buffer.size() >= BATCH_SIZE) {
            flush();
        }
    }

    public void flush() {
        if (buffer.isEmpty()) return;
        log.debug("Flushing {} points to Influx", buffer.size());
        try (WriteApi writeApi = influxClient.makeWriteApi(
                WriteOptions.builder().batchSize(buffer.size()).build())) {
            for (MinutePoint mp : buffer) {
                Point p = Point.measurement(mp.measurement())
                    .addTag(mp.tagKey(), mp.tagValue())
                    .addField("value", mp.value())
                    .time(mp.ts(), WritePrecision.S);
                writeApi.writePoint(p);
            }
        }
        buffer.clear();
    }
}
