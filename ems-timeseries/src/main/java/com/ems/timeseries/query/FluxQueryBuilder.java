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

    /**
     * 累积量 (CUMULATIVE_ENERGY) 区间合计：每个 meter_code 取 (last - first)。
     * 适用于电表底数 / odometer 寄存器（如安科瑞 0x003F）：单调递增，区间用量 = 末点 - 初点。
     *
     * <p>实现：先按 meter_code+energy_type 分组，分别取 first()/last()，pivot 后做差。
     * 返回行：meter_code, energy_type, _value（即区间能耗）。
     *
     * <p>简化假设：区间内不发生计数器归零 / rollover。后续如要兼容换表，可改用
     * {@code derivative(nonNegative: true) |> sum()}。
     */
    public static String cumulativeOverRange(
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

        return new StringBuilder(512)
            .append("data = from(bucket: \"").append(escapeQuotes(bucket)).append("\")\n")
            .append("  |> range(start: ").append(RFC3339.format(range.start()))
            .append(", stop: ").append(RFC3339.format(range.end())).append(")\n")
            .append("  |> filter(fn: (r) => r._measurement == \"").append(escapeQuotes(measurement)).append("\")\n")
            .append("  |> filter(fn: (r) => r._field == \"value\")\n")
            .append("  |> filter(fn: (r) => contains(value: r.meter_code, set: ").append(set).append("))\n")
            .append("  |> group(columns: [\"meter_code\", \"energy_type\"])\n")
            .append("\n")
            .append("first_v = data |> first() |> set(key: \"_field\", value: \"first\") |> drop(columns: [\"_time\"])\n")
            .append("last_v  = data |> last()  |> set(key: \"_field\", value: \"last\")  |> drop(columns: [\"_time\"])\n")
            .append("\n")
            .append("union(tables: [first_v, last_v])\n")
            .append("  |> pivot(rowKey: [\"meter_code\", \"energy_type\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n")
            .append("  |> map(fn: (r) => ({r with _value: r.last - r.first}))\n")
            .append("  |> keep(columns: [\"meter_code\", \"energy_type\", \"_value\"])")
            .toString();
    }

    /**
     * 累积电量 (CUMULATIVE_ENERGY) 时序桶聚合：每个 meter_code 在每个 bucket 内的能耗 (delta sum)。
     * 流程：分组 → difference(nonNegative=true) 把累积值转为相邻点的增量 → aggregateWindow(sum) 累加到桶。
     *
     * <p>nonNegative 处理换表/计数器归零：负差被丢弃，而非取绝对值。
     * 注意：第一个点没有前驱，会被 difference 丢掉；所以 range 起始处的桶可能略低估。
     */
    public static String bucketedDeltaForMeter(
        String bucket,
        String measurement,
        Collection<String> meterCodes,
        TimeRange range,
        Granularity granularity
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

        return new StringBuilder(384)
            .append("from(bucket: \"").append(escapeQuotes(bucket)).append("\")\n")
            .append("  |> range(start: ").append(RFC3339.format(range.start()))
            .append(", stop: ").append(RFC3339.format(range.end())).append(")\n")
            .append("  |> filter(fn: (r) => r._measurement == \"").append(escapeQuotes(measurement)).append("\")\n")
            .append("  |> filter(fn: (r) => r._field == \"value\")\n")
            .append("  |> filter(fn: (r) => contains(value: r.meter_code, set: ").append(set).append("))\n")
            .append("  |> group(columns: [\"meter_code\", \"energy_type\"])\n")
            .append("  |> difference(nonNegative: true)\n")
            .append("  |> aggregateWindow(every: ").append(granularity.fluxWindow())
            .append(", fn: sum, createEmpty: false)\n")
            .append("  |> keep(columns: [\"_time\", \"_value\", \"meter_code\", \"energy_type\"])")
            .toString();
    }

    /**
     * 瞬时功率 (INSTANT_POWER) 时序桶聚合：每个 meter_code 在每个 bucket 内的能耗 (功率梯形积分)。
     * 单位假设：value 列存 kW → 输出 kWh/桶；存 W → 输出 Wh/桶。
     */
    public static String bucketedIntegralForMeter(
        String bucket,
        String measurement,
        Collection<String> meterCodes,
        TimeRange range,
        Granularity granularity
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

        return new StringBuilder(384)
            .append("from(bucket: \"").append(escapeQuotes(bucket)).append("\")\n")
            .append("  |> range(start: ").append(RFC3339.format(range.start()))
            .append(", stop: ").append(RFC3339.format(range.end())).append(")\n")
            .append("  |> filter(fn: (r) => r._measurement == \"").append(escapeQuotes(measurement)).append("\")\n")
            .append("  |> filter(fn: (r) => r._field == \"value\")\n")
            .append("  |> filter(fn: (r) => contains(value: r.meter_code, set: ").append(set).append("))\n")
            .append("  |> aggregateWindow(every: ").append(granularity.fluxWindow())
            .append(", fn: (column, tables=<-) => tables |> integral(unit: 1h, column: column), createEmpty: false)\n")
            .append("  |> keep(columns: [\"_time\", \"_value\", \"meter_code\", \"energy_type\"])")
            .toString();
    }

    /**
     * 瞬时功率 (INSTANT_POWER) 区间合计：每个 meter_code 取功率序列对时间的积分。
     * 适用于安科瑞 0x0031 等瞬时功率寄存器：单位 kW → 积分输出 kWh。
     *
     * <p>实现：按 meter_code+energy_type 分组后调用 Flux integral(unit: 1h)。
     * integral 内部用相邻点的梯形积分；要求输入序列有 _time 列（range 之后自然具备）。
     *
     * <p>精度依赖采样间隔：5s 轮询的工业负载误差约 0.1%-0.5%；秒级突变负载会丢峰值。
     * 单位假设：value 列单位为 kW；如为 W，输出为 Wh，调用方需自行换算。
     */
    public static String integralOverRange(
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

        return new StringBuilder(384)
            .append("from(bucket: \"").append(escapeQuotes(bucket)).append("\")\n")
            .append("  |> range(start: ").append(RFC3339.format(range.start()))
            .append(", stop: ").append(RFC3339.format(range.end())).append(")\n")
            .append("  |> filter(fn: (r) => r._measurement == \"").append(escapeQuotes(measurement)).append("\")\n")
            .append("  |> filter(fn: (r) => r._field == \"value\")\n")
            .append("  |> filter(fn: (r) => contains(value: r.meter_code, set: ").append(set).append("))\n")
            // _start/_stop must stay in the group key — integral() on bounded ranges requires it.
            .append("  |> group(columns: [\"meter_code\", \"energy_type\", \"_start\", \"_stop\"])\n")
            .append("  |> integral(unit: 1h)\n")
            .append("  |> keep(columns: [\"meter_code\", \"energy_type\", \"_value\"])")
            .toString();
    }

    /**
     * 拉取指定 meter_code 在 [start, stop) 区间的所有原始点（用于 rollup 计算输入）。
     * 返回行：_time, _value, meter_code
     */
    public static String rawPointsForMeter(
        String bucket,
        String measurement,
        String meterCode,
        TimeRange range
    ) {
        require(bucket, "bucket");
        require(measurement, "measurement");
        if (meterCode == null || !SAFE_TAG.matcher(meterCode).matches()) {
            throw new IllegalArgumentException("非法 meter_code: " + meterCode);
        }
        return new StringBuilder(192)
            .append("from(bucket: \"").append(escapeQuotes(bucket)).append("\")\n")
            .append("  |> range(start: ").append(RFC3339.format(range.start()))
            .append(", stop: ").append(RFC3339.format(range.end())).append(")\n")
            .append("  |> filter(fn: (r) => r._measurement == \"").append(escapeQuotes(measurement)).append("\")\n")
            .append("  |> filter(fn: (r) => r._field == \"value\")\n")
            .append("  |> filter(fn: (r) => r.meter_code == \"").append(meterCode).append("\")\n")
            .append("  |> keep(columns: [\"_time\", \"_value\", \"meter_code\"])")
            .toString();
    }

    private static void require(String s, String name) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }

    private static String escapeQuotes(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
