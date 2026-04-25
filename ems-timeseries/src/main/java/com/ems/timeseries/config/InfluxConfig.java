package com.ems.timeseries.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InfluxProperties.class)
public class InfluxConfig {

    @Bean(destroyMethod = "close")
    public InfluxDBClient influxDBClient(InfluxProperties props) {
        return InfluxDBClientFactory.create(
            props.getUrl(),
            props.getToken().toCharArray(),
            props.getOrg(),
            props.getBucket()
        );
    }
}
