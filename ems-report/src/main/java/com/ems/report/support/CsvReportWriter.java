package com.ems.report.support;

import com.ems.report.dto.ReportRow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

/**
 * CSV 流式写出。Excel 兼容的 UTF-8 BOM 头 + 表头 + 数据行。
 * 调用方传入 OutputStream（StreamingResponseBody / ByteArrayOutputStream / FileOutputStream 均可）。
 */
public final class CsvReportWriter {

    /** UTF-8 BOM (EF BB BF) — 让 Excel 直接识别为 UTF-8 而不是 ANSI 中文乱码。 */
    public static final byte[] UTF8_BOM = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    public static final String[] HEADERS = {
        "timestamp", "meter_id", "meter_code", "meter_name",
        "org_node_id", "energy_type", "unit", "value"
    };

    private CsvReportWriter() {}

    /** 将行流写入 out。流写完会被 close。out 由调用方负责关闭。 */
    public static void write(Stream<ReportRow> rows, OutputStream out) {
        try {
            out.write(UTF8_BOM);
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            try (CSVPrinter printer = new CSVPrinter(w, CSVFormat.DEFAULT.builder().setHeader(HEADERS).build())) {
                try (Stream<ReportRow> r = rows) {
                    r.forEach(row -> printRow(printer, row));
                }
                printer.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void printRow(CSVPrinter printer, ReportRow row) {
        try {
            printer.printRecord(
                row.ts(),                // ISO-8601, e.g. 2026-04-25T00:00:00Z
                row.meterId(),
                row.meterCode(),
                row.meterName(),
                row.orgNodeId(),
                row.energyTypeCode(),
                row.unit(),
                row.value()
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
