package com.ems.collector.channel.csv;

import com.ems.collector.channel.Channel;
import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.protocol.ModbusPoint;
import com.ems.collector.protocol.ModbusRtuConfig;
import com.ems.collector.protocol.ModbusTcpConfig;
import com.ems.collector.protocol.MqttConfig;
import com.ems.collector.protocol.MqttPoint;
import com.ems.collector.protocol.OpcUaConfig;
import com.ems.collector.protocol.OpcUaPoint;
import com.ems.collector.protocol.SecurityMode;
import com.ems.collector.protocol.SubscriptionMode;
import com.ems.collector.protocol.VirtualConfig;
import com.ems.collector.protocol.VirtualMode;
import com.ems.collector.protocol.VirtualPoint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * channels.csv + points.csv → {@code List<Channel>}（5 个协议都支持）。
 *
 * <p>channels.csv 一行一个 channel；points.csv 一行一个测点（按 {@code channelName} 关联）。
 * channels.csv 的列宽 = 5 个协议字段并集；某行只填它对应协议需要的列，其它留空。
 *
 * <p>VirtualPoint.params 是 {@code Map<String,Double>}——CSV 没法直接拍平，统一用一列
 * {@code virtualParams} 存 JSON 字符串（例如 {@code {"baseValue":100,"amplitude":10}}）。
 *
 * <p>设计取舍：parser 只生成内存中的 Channel，不落库。Controller 把结果序列化成 ChannelDTO[] 给前端，
 * 前端逐行 POST /channel——这样可以复用现有"逐行状态表 + 同名跳过 409"的导入 UX。
 */
public final class ChannelCsvParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CHANNEL_REQUIRED = Set.of("name", "protocol");
    private static final Set<String> POINT_REQUIRED = Set.of("channelName", "key");

    private ChannelCsvParser() {}

    public static List<Channel> parse(InputStream channelsCsv, InputStream pointsCsv) throws IOException {
        Map<String, List<CSVRecord>> pointsByChannel = collectPoints(pointsCsv);
        return parseChannels(channelsCsv, pointsByChannel);
    }

    // ── channels.csv ────────────────────────────────────────────────────

    private static List<Channel> parseChannels(InputStream in, Map<String, List<CSVRecord>> ptMap)
            throws IOException {
        Reader reader = new InputStreamReader(stripBom(in), StandardCharsets.UTF_8);
        CSVFormat fmt = CSVFormat.DEFAULT.builder()
            .setHeader().setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true).setTrim(true).build();
        List<Channel> result = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        try (var p = fmt.parse(reader)) {
            checkRequiredHeaders(p.getHeaderMap().keySet(), CHANNEL_REQUIRED, "channels.csv");
            for (CSVRecord r : p) {
                if (isBlankRow(r)) continue;
                long line = r.getRecordNumber() + 1;
                Channel ch = parseChannelRow(r, line, ptMap);
                if (!seenNames.add(ch.getName())) {
                    throw new IllegalArgumentException(
                        "channels.csv 第 " + line + " 行 name 重复：" + ch.getName());
                }
                result.add(ch);
            }
        }
        return result;
    }

    private static Channel parseChannelRow(CSVRecord r, long line, Map<String, List<CSVRecord>> ptMap) {
        String name = required(r, "name", line, "channels.csv");
        String protocol = required(r, "protocol", line, "channels.csv").toUpperCase(Locale.ROOT);
        boolean enabled = parseBool(opt(r, "enabled"), true);
        boolean isVirtual = parseBool(opt(r, "isVirtual"), false);
        String description = opt(r, "description");

        List<CSVRecord> raw = ptMap.getOrDefault(name, List.of());
        ChannelConfig cfg = switch (protocol) {
            case "MODBUS_TCP" -> buildModbusTcp(r, line, raw);
            case "MODBUS_RTU" -> buildModbusRtu(r, line, raw);
            case "OPC_UA" -> buildOpcUa(r, line, raw);
            case "MQTT" -> buildMqtt(r, line, raw);
            case "VIRTUAL" -> buildVirtual(r, line, raw);
            default -> throw new IllegalArgumentException(
                "channels.csv 第 " + line + " 行 protocol 非法：" + protocol);
        };

        Channel ch = new Channel();
        ch.setName(name);
        ch.setProtocol(protocol);
        ch.setEnabled(enabled);
        ch.setVirtual(isVirtual);
        ch.setDescription(description);
        ch.setProtocolConfig(cfg);
        return ch;
    }

    private static ModbusTcpConfig buildModbusTcp(CSVRecord r, long line, List<CSVRecord> raw) {
        return new ModbusTcpConfig(
            required(r, "host", line, "channels.csv"),
            parseInt(safeGet(r, "port"), "port", line, "channels.csv"),
            parseIntOpt(opt(r, "unitId"), "unitId", line, 1),
            parseDuration(r, "pollInterval", line),
            parseDurationOpt(r, "timeout"),
            raw.stream().map(ChannelCsvParser::toModbusPoint).toList()
        );
    }

    private static ModbusRtuConfig buildModbusRtu(CSVRecord r, long line, List<CSVRecord> raw) {
        return new ModbusRtuConfig(
            required(r, "serialPort", line, "channels.csv"),
            parseInt(safeGet(r, "baudRate"), "baudRate", line, "channels.csv"),
            parseIntOpt(opt(r, "dataBits"), "dataBits", line, 8),
            parseIntOpt(opt(r, "stopBits"), "stopBits", line, 1),
            optOrDefault(r, "parity", "NONE"),
            parseIntOpt(opt(r, "unitId"), "unitId", line, 1),
            parseDuration(r, "pollInterval", line),
            parseDurationOpt(r, "timeout"),
            raw.stream().map(ChannelCsvParser::toModbusPoint).toList()
        );
    }

    private static OpcUaConfig buildOpcUa(CSVRecord r, long line, List<CSVRecord> raw) {
        SecurityMode sec = parseEnum(SecurityMode.class,
            optOrDefault(r, "securityMode", "NONE"), "securityMode", line);
        return new OpcUaConfig(
            required(r, "endpointUrl", line, "channels.csv"),
            sec,
            opt(r, "certRef"),
            opt(r, "certPasswordRef"),
            opt(r, "usernameRef"),
            opt(r, "passwordRef"),
            parseDurationOpt(r, "pollInterval"),
            raw.stream().map(ChannelCsvParser::toOpcUaPoint).toList()
        );
    }

    private static MqttConfig buildMqtt(CSVRecord r, long line, List<CSVRecord> raw) {
        return new MqttConfig(
            required(r, "brokerUrl", line, "channels.csv"),
            required(r, "clientId", line, "channels.csv"),
            opt(r, "usernameRef"),
            opt(r, "passwordRef"),
            opt(r, "tlsCaCertRef"),
            parseIntOpt(opt(r, "qos"), "qos", line, 0),
            parseBool(opt(r, "cleanSession"), true),
            parseDuration(r, "keepAlive", line),
            opt(r, "lastWillTopic"),
            opt(r, "lastWillPayload"),
            parseIntOpt(opt(r, "lastWillQos"), "lastWillQos", line, 0),
            parseBool(opt(r, "lastWillRetained"), false),
            raw.stream().map(ChannelCsvParser::toMqttPoint).toList()
        );
    }

    private static VirtualConfig buildVirtual(CSVRecord r, long line, List<CSVRecord> raw) {
        return new VirtualConfig(
            parseDuration(r, "pollInterval", line),
            raw.stream().map(ChannelCsvParser::toVirtualPoint).toList()
        );
    }

    // ── points.csv ──────────────────────────────────────────────────────

    private static Map<String, List<CSVRecord>> collectPoints(InputStream in) throws IOException {
        Reader reader = new InputStreamReader(stripBom(in), StandardCharsets.UTF_8);
        CSVFormat fmt = CSVFormat.DEFAULT.builder()
            .setHeader().setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true).setTrim(true).build();
        Map<String, List<CSVRecord>> map = new LinkedHashMap<>();
        try (var p = fmt.parse(reader)) {
            checkRequiredHeaders(p.getHeaderMap().keySet(), POINT_REQUIRED, "points.csv");
            for (CSVRecord r : p) {
                if (isBlankRow(r)) continue;
                long line = r.getRecordNumber() + 1;
                String chName = required(r, "channelName", line, "points.csv");
                map.computeIfAbsent(chName, k -> new ArrayList<>()).add(r);
            }
        }
        return map;
    }

    private static ModbusPoint toModbusPoint(CSVRecord r) {
        long line = r.getRecordNumber() + 1;
        return new ModbusPoint(
            required(r, "key", line, "points.csv"),
            optOrDefault(r, "registerKind", "HOLDING").toUpperCase(Locale.ROOT),
            parseInt(safeGet(r, "address"), "address", line, "points.csv"),
            parseIntOpt(opt(r, "quantity"), "quantity", line, 1),
            optOrDefault(r, "dataType", "INT16"),
            optOrDefault(r, "byteOrder", "BIG_ENDIAN"),
            parseDoubleOpt(opt(r, "scale"), 1.0),
            opt(r, "unit")
        );
    }

    private static OpcUaPoint toOpcUaPoint(CSVRecord r) {
        long line = r.getRecordNumber() + 1;
        SubscriptionMode mode = parseEnum(SubscriptionMode.class,
            optOrDefault(r, "mode", "READ"), "mode", line);
        return new OpcUaPoint(
            required(r, "key", line, "points.csv"),
            required(r, "nodeId", line, "points.csv"),
            mode,
            parseDoubleOpt(opt(r, "samplingIntervalMs"), null),
            opt(r, "unit")
        );
    }

    private static MqttPoint toMqttPoint(CSVRecord r) {
        long line = r.getRecordNumber() + 1;
        return new MqttPoint(
            required(r, "key", line, "points.csv"),
            required(r, "topic", line, "points.csv"),
            required(r, "jsonPath", line, "points.csv"),
            opt(r, "unit"),
            opt(r, "timestampJsonPath")
        );
    }

    private static VirtualPoint toVirtualPoint(CSVRecord r) {
        long line = r.getRecordNumber() + 1;
        VirtualMode mode = parseEnum(VirtualMode.class,
            required(r, "virtualMode", line, "points.csv"), "virtualMode", line);
        Map<String, Double> params = parseVirtualParams(opt(r, "virtualParams"), line);
        return new VirtualPoint(
            required(r, "key", line, "points.csv"),
            mode,
            params,
            opt(r, "unit")
        );
    }

    private static Map<String, Double> parseVirtualParams(String json, long line) {
        if (json == null) return Map.of();
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Double>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "points.csv 第 " + line + " 行 virtualParams 不是合法 JSON：" + json);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static void checkRequiredHeaders(Set<String> headers, Set<String> required, String file) {
        List<String> missing = new ArrayList<>();
        for (String h : required) {
            if (!headers.contains(h)) missing.add(h);
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(file + " 表头缺少必填列：" + String.join(", ", missing));
        }
    }

    private static String required(CSVRecord r, String col, long line, String file) {
        String v = r.isMapped(col) ? r.get(col) : null;
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(file + " 第 " + line + " 行 " + col + " 不能为空");
        }
        return v;
    }

    private static String opt(CSVRecord r, String col) {
        if (!r.isMapped(col)) return null;
        String v = r.get(col);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String safeGet(CSVRecord r, String col) {
        return r.isMapped(col) ? r.get(col) : null;
    }

    private static String optOrDefault(CSVRecord r, String col, String def) {
        String v = opt(r, col);
        return v == null ? def : v;
    }

    private static int parseInt(String v, String col, long line, String file) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(file + " 第 " + line + " 行 " + col + " 不能为空");
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                file + " 第 " + line + " 行 " + col + " 不是合法整数：" + v);
        }
    }

    private static int parseIntOpt(String v, String col, long line, int def) {
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "第 " + line + " 行 " + col + " 不是合法整数：" + v);
        }
    }

    private static Double parseDoubleOpt(String v, Double def) {
        if (v == null || v.isBlank()) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不是合法浮点数：" + v);
        }
    }

    private static Duration parseDuration(CSVRecord r, String col, long line) {
        String v = required(r, col, line, "channels.csv");
        try {
            return Duration.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("channels.csv 第 " + line + " 行 " + col
                + " 不是合法 ISO-8601 Duration（例 PT60S/PT2M）：" + v);
        }
    }

    private static Duration parseDurationOpt(CSVRecord r, String col) {
        String v = opt(r, col);
        if (v == null) return null;
        try {
            return Duration.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(col + " 不是合法 ISO-8601 Duration：" + v);
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> cls, String v, String col, long line) {
        try {
            return Enum.valueOf(cls, v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "第 " + line + " 行 " + col + " 取值非法：" + v);
        }
    }

    private static boolean parseBool(String v, boolean def) {
        if (v == null || v.isBlank()) return def;
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "是", "yes", "y" -> true;
            case "false", "0", "否", "no", "n" -> false;
            default -> Boolean.parseBoolean(v);
        };
    }

    private static boolean isBlankRow(CSVRecord r) {
        for (int i = 0; i < r.size(); i++) {
            if (!r.get(i).isBlank()) return false;
        }
        return true;
    }

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
