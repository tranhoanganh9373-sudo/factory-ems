package com.ems.timeseries.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ems.influx")
public class InfluxProperties {

    @NotBlank private String url = "http://localhost:8086";
    @NotBlank private String token = "changeme";
    @NotBlank private String org = "factory";
    @NotBlank private String bucket = "factory_ems";
    private String measurement = "energy_reading";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getOrg() { return org; }
    public void setOrg(String org) { this.org = org; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getMeasurement() { return measurement; }
    public void setMeasurement(String measurement) { this.measurement = measurement; }
}
