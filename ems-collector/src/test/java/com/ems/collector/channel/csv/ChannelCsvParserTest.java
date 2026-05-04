package com.ems.collector.channel.csv;

import com.ems.collector.channel.Channel;
import com.ems.collector.protocol.ModbusPoint;
import com.ems.collector.protocol.ModbusRtuConfig;
import com.ems.collector.protocol.ModbusTcpConfig;
import com.ems.collector.protocol.MqttConfig;
import com.ems.collector.protocol.OpcUaConfig;
import com.ems.collector.protocol.SecurityMode;
import com.ems.collector.protocol.SubscriptionMode;
import com.ems.collector.protocol.VirtualConfig;
import com.ems.collector.protocol.VirtualMode;
import com.ems.collector.protocol.VirtualPoint;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelCsvParserTest {

    @Test
    void parse_modbusTcp_withPoints() throws Exception {
        String channelsCsv = """
                name,protocol,enabled,description,host,port,unitId,pollInterval,timeout
                1F-MCC,MODBUS_TCP,true,1F 配电室,10.0.1.11,502,1,PT60S,PT2S
                """;
        String pointsCsv = """
                channelName,key,registerKind,address,quantity,dataType,byteOrder,scale,unit
                1F-MCC,p1,HOLDING,100,1,INT16,BIG_ENDIAN,1.0,kWh
                1F-MCC,p2,HOLDING,102,2,FLOAT32,BIG_ENDIAN,0.1,kW
                """;
        List<Channel> chs = ChannelCsvParser.parse(stream(channelsCsv), stream(pointsCsv));
        assertThat(chs).hasSize(1);
        Channel c = chs.get(0);
        assertThat(c.getName()).isEqualTo("1F-MCC");
        assertThat(c.getProtocol()).isEqualTo("MODBUS_TCP");
        assertThat(c.isEnabled()).isTrue();
        ModbusTcpConfig cfg = (ModbusTcpConfig) c.getProtocolConfig();
        assertThat(cfg.host()).isEqualTo("10.0.1.11");
        assertThat(cfg.port()).isEqualTo(502);
        assertThat(cfg.pollInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(cfg.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(cfg.points()).hasSize(2);
        ModbusPoint p0 = cfg.points().get(0);
        assertThat(p0.key()).isEqualTo("p1");
        assertThat(p0.address()).isEqualTo(100);
        assertThat(p0.quantity()).isEqualTo(1);
    }

    @Test
    void parse_virtual_withParams() throws Exception {
        String channelsCsv = """
                name,protocol,pollInterval
                sim1,VIRTUAL,PT5S
                """;
        String pointsCsv = """
                channelName,key,virtualMode,virtualParams,unit
                sim1,sine1,SINE,"{""baseValue"":100,""amplitude"":10}",kW
                sim1,const1,CONSTANT,"{""value"":42}",kW
                """;
        List<Channel> chs = ChannelCsvParser.parse(stream(channelsCsv), stream(pointsCsv));
        VirtualConfig cfg = (VirtualConfig) chs.get(0).getProtocolConfig();
        assertThat(cfg.points()).hasSize(2);
        VirtualPoint p0 = cfg.points().get(0);
        assertThat(p0.mode()).isEqualTo(VirtualMode.SINE);
        assertThat(p0.params()).containsEntry("baseValue", 100.0).containsEntry("amplitude", 10.0);
    }

    @Test
    void parse_opcUa_withSubscribePoints() throws Exception {
        String channelsCsv = """
                name,protocol,endpointUrl,securityMode
                opc1,OPC_UA,opc.tcp://1.2.3.4:4840,NONE
                """;
        String pointsCsv = """
                channelName,key,nodeId,mode,samplingIntervalMs,unit
                opc1,t1,ns=2;s=Tag1,SUBSCRIBE,500,°C
                """;
        List<Channel> chs = ChannelCsvParser.parse(stream(channelsCsv), stream(pointsCsv));
        OpcUaConfig cfg = (OpcUaConfig) chs.get(0).getProtocolConfig();
        assertThat(cfg.endpointUrl()).isEqualTo("opc.tcp://1.2.3.4:4840");
        assertThat(cfg.securityMode()).isEqualTo(SecurityMode.NONE);
        assertThat(cfg.points().get(0).mode()).isEqualTo(SubscriptionMode.SUBSCRIBE);
    }

    @Test
    void parse_mqtt_withPoints() throws Exception {
        String channelsCsv = """
                name,protocol,brokerUrl,clientId,qos,cleanSession,keepAlive
                mq1,MQTT,tcp://broker:1883,client-1,1,true,PT30S
                """;
        String pointsCsv = """
                channelName,key,topic,jsonPath,unit
                mq1,t1,sensors/t1,$.value,°C
                """;
        List<Channel> chs = ChannelCsvParser.parse(stream(channelsCsv), stream(pointsCsv));
        MqttConfig cfg = (MqttConfig) chs.get(0).getProtocolConfig();
        assertThat(cfg.brokerUrl()).isEqualTo("tcp://broker:1883");
        assertThat(cfg.qos()).isEqualTo(1);
        assertThat(cfg.points().get(0).jsonPath()).isEqualTo("$.value");
    }

    @Test
    void parse_modbusRtu_withDefaults() throws Exception {
        String channelsCsv = """
                name,protocol,serialPort,baudRate,pollInterval
                rtu1,MODBUS_RTU,/dev/ttyUSB0,9600,PT60S
                """;
        String pointsCsv = """
                channelName,key,address
                rtu1,p1,100
                """;
        List<Channel> chs = ChannelCsvParser.parse(stream(channelsCsv), stream(pointsCsv));
        ModbusRtuConfig cfg = (ModbusRtuConfig) chs.get(0).getProtocolConfig();
        assertThat(cfg.serialPort()).isEqualTo("/dev/ttyUSB0");
        assertThat(cfg.baudRate()).isEqualTo(9600);
        assertThat(cfg.dataBits()).isEqualTo(8);
        assertThat(cfg.parity()).isEqualTo("NONE");
    }

    @Test
    void parse_missingChannelsHeader_throws() {
        String channelsCsv = "name\nfoo\n";
        String pointsCsv = "channelName,key\nfoo,k1\n";
        assertThatThrownBy(() -> ChannelCsvParser.parse(stream(channelsCsv), stream(pointsCsv)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("channels.csv")
            .hasMessageContaining("protocol");
    }

    @Test
    void parse_missingPointsHeader_throws() {
        String channelsCsv = "name,protocol\nfoo,VIRTUAL\n";
        String pointsCsv = "channelName\nfoo\n";
        assertThatThrownBy(() -> ChannelCsvParser.parse(stream(channelsCsv), stream(pointsCsv)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("points.csv")
            .hasMessageContaining("key");
    }

    @Test
    void parse_invalidProtocol_throws() {
        String channelsCsv = "name,protocol\nfoo,XYZ\n";
        String pointsCsv = "channelName,key\n";
        assertThatThrownBy(() -> ChannelCsvParser.parse(stream(channelsCsv), stream(pointsCsv)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("protocol 非法");
    }

    @Test
    void parse_duplicateChannelName_throws() {
        String channelsCsv = """
                name,protocol,pollInterval
                a,VIRTUAL,PT1S
                a,VIRTUAL,PT1S
                """;
        String pointsCsv = """
                channelName,key,virtualMode,virtualParams
                a,k1,CONSTANT,"{""value"":1}"
                """;
        assertThatThrownBy(() -> ChannelCsvParser.parse(stream(channelsCsv), stream(pointsCsv)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name 重复");
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
