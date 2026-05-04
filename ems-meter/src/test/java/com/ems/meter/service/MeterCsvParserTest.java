package com.ems.meter.service;

import com.ems.meter.dto.MeterImportRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeterCsvParserTest {

    @Test
    void parse_validCsv_returnsRows() throws Exception {
        String csv = """
                code,name,energyTypeId,orgNodeId,enabled,channelName
                E-1F-001,1F-MCC1 总表,1,5,true,1F-MCC-485
                E-2F-001,2F-MCC1 总表,1,6,false,2F-MCC-485
                """;
        List<MeterImportRow> rows = MeterCsvParser.parse(toStream(csv));
        assertThat(rows).hasSize(2);
        MeterImportRow r0 = rows.get(0);
        assertThat(r0.code()).isEqualTo("E-1F-001");
        assertThat(r0.name()).isEqualTo("1F-MCC1 总表");
        assertThat(r0.energyTypeId()).isEqualTo(1L);
        assertThat(r0.orgNodeId()).isEqualTo(5L);
        assertThat(r0.enabled()).isTrue();
        assertThat(r0.channelName()).isEqualTo("1F-MCC-485");
        assertThat(rows.get(1).enabled()).isFalse();
    }

    @Test
    void parse_missingRequiredHeader_throws() {
        String csv = "code,name,orgNodeId\nE-1,1F,5\n";
        assertThatThrownBy(() -> MeterCsvParser.parse(toStream(csv)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("energyTypeId");
    }

    @Test
    void parse_emptyChannelName_returnsNull() throws Exception {
        String csv = """
                code,name,energyTypeId,orgNodeId,enabled,channelName
                E-X,X 表,1,5,true,
                """;
        List<MeterImportRow> rows = MeterCsvParser.parse(toStream(csv));
        assertThat(rows.get(0).channelName()).isNull();
    }

    @Test
    void parse_channelNameColumnOptional() throws Exception {
        String csv = """
                code,name,energyTypeId,orgNodeId,enabled
                E-X,X 表,1,5,true
                """;
        List<MeterImportRow> rows = MeterCsvParser.parse(toStream(csv));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).channelName()).isNull();
        assertThat(rows.get(0).channelPointKey()).isNull();
    }

    @Test
    void parse_channelPointKeyColumn_isParsed() throws Exception {
        String csv = """
                code,name,energyTypeId,orgNodeId,enabled,channelName,channelPointKey
                E-1,1,1,5,true,1F-MCC-485,v1.power
                E-2,2,1,5,true,1F-MCC-485,
                """;
        List<MeterImportRow> rows = MeterCsvParser.parse(toStream(csv));
        assertThat(rows.get(0).channelPointKey()).isEqualTo("v1.power");
        assertThat(rows.get(1).channelPointKey()).isNull();
    }

    @Test
    void parse_enabledColumnOptional_defaultsTrue() throws Exception {
        String csv = """
                code,name,energyTypeId,orgNodeId
                E-X,X 表,1,5
                """;
        List<MeterImportRow> rows = MeterCsvParser.parse(toStream(csv));
        assertThat(rows.get(0).enabled()).isTrue();
    }

    @Test
    void parse_enabledAcceptsZeroOneAndChinese() throws Exception {
        String csv = """
                code,name,energyTypeId,orgNodeId,enabled,channelName
                A,A,1,5,1,
                B,B,1,5,0,
                C,C,1,5,是,
                D,D,1,5,否,
                """;
        List<MeterImportRow> rows = MeterCsvParser.parse(toStream(csv));
        assertThat(rows.get(0).enabled()).isTrue();
        assertThat(rows.get(1).enabled()).isFalse();
        assertThat(rows.get(2).enabled()).isTrue();
        assertThat(rows.get(3).enabled()).isFalse();
    }

    @Test
    void parse_skipsBlankRows() throws Exception {
        String csv = """
                code,name,energyTypeId,orgNodeId,enabled,channelName
                E-1,1,1,5,true,
                ,,,,,
                E-2,2,1,5,true,
                """;
        List<MeterImportRow> rows = MeterCsvParser.parse(toStream(csv));
        assertThat(rows).hasSize(2);
    }

    @Test
    void parse_tolerateUtf8Bom() throws Exception {
        String csv = "﻿code,name,energyTypeId,orgNodeId,enabled,channelName\nE-1,1,1,5,true,\n";
        List<MeterImportRow> rows = MeterCsvParser.parse(toStream(csv));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).code()).isEqualTo("E-1");
    }

    @Test
    void parse_invalidNumber_throwsWithRowContext() {
        String csv = """
                code,name,energyTypeId,orgNodeId,enabled,channelName
                E-1,1,abc,5,true,
                """;
        assertThatThrownBy(() -> MeterCsvParser.parse(toStream(csv)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("第 2 行")
            .hasMessageContaining("energyTypeId");
    }

    @Test
    void parse_missingRequiredCellValue_throwsWithRowContext() {
        String csv = """
                code,name,energyTypeId,orgNodeId,enabled,channelName
                ,1,1,5,true,
                """;
        assertThatThrownBy(() -> MeterCsvParser.parse(toStream(csv)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("第 2 行")
            .hasMessageContaining("code");
    }

    private static InputStream toStream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
