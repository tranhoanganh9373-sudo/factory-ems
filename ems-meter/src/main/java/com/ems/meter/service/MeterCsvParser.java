package com.ems.meter.service;

import com.ems.core.constant.ValueKind;
import com.ems.meter.dto.MeterImportRow;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.MeterRole;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * meters.csv → {@link MeterImportRow} 列表。
 *
 * <p>表头必填：code, name, energyTypeId, orgNodeId。
 * 可选：enabled（默认 true）、channelName（默认 null，前端再 resolve 到 channelId）、
 * channelPointKey（默认 null；当 channelName 提供时表示该 meter 在该 channel 下绑定的 point.key）。
 *
 * <p>InfluxDB 三字段（measurement/tagKey/tagValue）由 service 层强制写入约定值，
 * 不再开放给用户填写——见 MeterServiceImpl 的 INFLUX_TAG_KEY 注释。
 *
 * <p>BOM/空白行容错；非法整数或必填空值会抛带行号的 IllegalArgumentException，让前端能精确定位。
 */
public final class MeterCsvParser {

    private static final Set<String> REQUIRED_HEADERS = Set.of(
        "code", "name", "energyTypeId", "orgNodeId"
    );

    private MeterCsvParser() {}

    public static List<MeterImportRow> parse(InputStream input) throws IOException {
        Reader reader = new InputStreamReader(stripBom(input), StandardCharsets.UTF_8);

        CSVFormat fmt = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build();

        List<MeterImportRow> rows = new ArrayList<>();
        try (org.apache.commons.csv.CSVParser parser = fmt.parse(reader)) {
            Set<String> headers = parser.getHeaderMap().keySet();
            List<String> missing = new ArrayList<>();
            for (String required : REQUIRED_HEADERS) {
                if (!headers.contains(required)) missing.add(required);
            }
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("CSV 表头缺少必填列：" + String.join(", ", missing));
            }
            boolean hasEnabled = headers.contains("enabled");
            boolean hasChannelName = headers.contains("channelName");
            boolean hasChannelPointKey = headers.contains("channelPointKey");
            boolean hasValueKind = headers.contains("valueKind");
            boolean hasRole = headers.contains("role");
            boolean hasEnergySource = headers.contains("energySource");
            boolean hasFlowDirection = headers.contains("flowDirection");

            for (CSVRecord r : parser) {
                if (isBlankRow(r)) continue;
                // commons-csv getRecordNumber() 是数据行序号（不含表头），+1 得到 CSV 实际行号
                long line = r.getRecordNumber() + 1;
                rows.add(parseRow(r, line, hasEnabled, hasChannelName, hasChannelPointKey, hasValueKind,
                    hasRole, hasEnergySource, hasFlowDirection));
            }
        }
        return rows;
    }

    private static boolean isBlankRow(CSVRecord r) {
        for (int i = 0; i < r.size(); i++) {
            if (!r.get(i).isBlank()) return false;
        }
        return true;
    }

    private static MeterImportRow parseRow(CSVRecord r, long line,
                                           boolean hasEnabled, boolean hasChannelName,
                                           boolean hasChannelPointKey, boolean hasValueKind,
                                           boolean hasRole, boolean hasEnergySource,
                                           boolean hasFlowDirection) {
        String code = required(r, "code", line);
        String name = required(r, "name", line);
        Long energyTypeId = parseLong(r.get("energyTypeId"), "energyTypeId", line);
        Long orgNodeId = parseLong(r.get("orgNodeId"), "orgNodeId", line);
        Boolean enabled = hasEnabled ? parseBool(r.get("enabled")) : Boolean.TRUE;
        String channelName = hasChannelName ? blankToNull(r.get("channelName")) : null;
        String channelPointKey = hasChannelPointKey ? blankToNull(r.get("channelPointKey")) : null;
        ValueKind valueKind = hasValueKind ? parseValueKind(r.get("valueKind"), line) : null;
        MeterRole role = hasRole ? parseRole(r.get("role")) : null;
        EnergySource source = hasEnergySource ? parseSource(r.get("energySource")) : null;
        FlowDirection direction = hasFlowDirection ? parseDir(r.get("flowDirection")) : null;
        return new MeterImportRow(code, name, energyTypeId, orgNodeId, enabled, channelName, channelPointKey,
            valueKind, role, source, direction);
    }

    private static ValueKind parseValueKind(String v, long line) {
        if (v == null || v.isBlank()) return null;
        try {
            return ValueKind.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "第 " + line + " 行 valueKind 不是合法值（INTERVAL_DELTA / CUMULATIVE_ENERGY / INSTANT_POWER）：" + v);
        }
    }

    private static MeterRole parseRole(String v) {
        if (v == null || v.isBlank()) return null;
        return MeterRole.valueOf(v.trim().toUpperCase());
    }

    private static EnergySource parseSource(String v) {
        if (v == null || v.isBlank()) return null;
        return EnergySource.valueOf(v.trim().toUpperCase());
    }

    private static FlowDirection parseDir(String v) {
        if (v == null || v.isBlank()) return null;
        return FlowDirection.valueOf(v.trim().toUpperCase());
    }

    private static String required(CSVRecord r, String column, long line) {
        String v = r.get(column);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("第 " + line + " 行 " + column + " 不能为空");
        }
        return v;
    }

    private static Long parseLong(String v, String column, long line) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("第 " + line + " 行 " + column + " 不能为空");
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "第 " + line + " 行 " + column + " 不是合法整数：" + v);
        }
    }

    private static Boolean parseBool(String v) {
        if (v == null || v.isBlank()) return Boolean.TRUE;
        String s = v.trim().toLowerCase();
        return switch (s) {
            case "true", "1", "是", "yes", "y" -> Boolean.TRUE;
            case "false", "0", "否", "no", "n" -> Boolean.FALSE;
            default -> Boolean.parseBoolean(s);
        };
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }

    /** UTF-8 BOM (EF BB BF) 容错：消费掉就丢掉，否则把字节回填回流。 */
    private static InputStream stripBom(InputStream in) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(in, 3);
        byte[] bom = new byte[3];
        int read = pb.read(bom);
        if (read == 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
            return pb;
        }
        if (read > 0) pb.unread(bom, 0, read);
        return pb;
    }
}
