package com.ems.timeseries.query;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.TimeRange;

import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 构造 InfluxDB 2.x Flux 查询字符串。
 * 仅在 ems-timeseries 内使用；其他模块只通过 TimeSeriesQueryService。
 *
 * 防注入：tag 值仅允许 [A-Za-z0-9_-]+，长度 ≤ 64；不符合则抛 IllegalArgumentException。
 */
public final class FluxQueryBuilder {

    private static final Pattern SAFE_TAG = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_INSTANT;

    public enum Agg {
        SUM("sum"),
        MEAN("mean"),
        MAX("max"),
        MIN("min");
        final String fn;
        Agg(String fn) { this.fn = fn; }
    }

    private FluxQueryBuilder() {}

    /**
     * 按 meter_code 列表 + 时间范围 + 粒度 + 聚合函数构造查询。
     * 返回行：_time, _value, meter_code, energy_type
     */
    public static String aggregateByMeter(
        String bucket,
        String measurement,
        Collection<String> meterCodes,
        TimeRange range,
        Granularity granularity,
        Agg agg
    ) {
        require(bucket, "bucket");
        require(measurement, "measurement");
        if (meterCodes == null || meterCodes.isEmpty()) {
            throw new IllegalArgumentException("meterCodes 不能为空");
        }
        for (String mc : meterCodes) {
            if (mc == null || !SAFE_TAG.matcher(mc).matches()) {
                throw new IllegalArgumentException("非法 meter_code: " + mc);
            }
        }
        String set = meterCodes.stream()
            .map(c -> "\"" + c + "\"")
            .collect(Collectors.joining(", ", "[", "]"));

        return new StringBuilder(256)
            .append("from(bucket: \"").append(escapeQuotes(bucket)).append("\")\n")
            .append("  |> range(start: ").append(RFC3339.format(range.start()))
            .append(", stop: ").append(RFC3339.format(range.end())).append(")\n")
            .append("  |> filter(fn: (r) => r._measurement == \"").append(escapeQuotes(measurement)).append("\")\n")
            .append("  |> filter(fn: (r) => r._field == \"value\")\n")
            .append("  |> filter(fn: (r) => contains(value: r.meter_code, set: ").append(set).append("))\n")
            .append("  |> aggregateWindow(every: ").append(granularity.fluxWindow())
            .append(", fn: ").append(agg.fn).append(", createEmpty: false)\n")
            .append("  |> keep(columns: [\"_time\", \"_value\", \"meter_code\", \"energy_type\"])")
            .toString();
    }

    /**
     * 区间合计（不分桶）：每个 meter_code 一行 _value。
     */
    public static String sumOverRange(
        String bucket,
        String measurement,
        Collection<String> meterCodes,
        TimeRange range
    ) {
        require(bucket, "bucket");
        require(measurement, "measurement");
        if (meterCodes == null || meterCodes.isEmpty()) {
            throw new IllegalArgumentException("meterCodes 不能为空");
        }
        for (String mc : meterCodes) {
            if (mc == null || !SAFE_TAG.matcher(mc).matches()) {
                throw new IllegalArgumentException("非法 meter_code: " + mc);
            }
        }
        String set = meterCodes.stream()
            .map(c -> "\"" + c + "\"")
            .collect(Collectors.joining(", ", "[", "]"));

        return new StringBuilder(256)
            .append("from(bucket: \"").append(escapeQuotes(bucket)).append("\")\n")
            .append("  |> range(start: ").append(RFC3339.format(range.start()))
            .append(", stop: ").append(RFC3339.format(range.end())).append(")\n")
            .append("  |> filter(fn: (r) => r._measurement == \"").append(escapeQuotes(measurement)).append("\")\n")
            .append("  |> filter(fn: (r) => r._field == \"value\")\n")
            .append("  |> filter(fn: (r) => contains(value: r.meter_code, set: ").append(set).append("))\n")
            .append("  |> group(columns: [\"meter_code\", \"energy_type\"])\n")
            .append("  |> sum()\n")
            .append("  |> keep(columns: [\"meter_code\", \"energy_type\", \"_value\"])")
            .toString();
    }

    private static void require(String s, String name) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }

    private static String escapeQuotes(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
