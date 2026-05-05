package com.ems.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * v1.2.0 PV 总闸。enabled=false 时所有 PV 计算路径短路（KPI 走老公式），
 * 但 schema 默认值已生效，老 meter 行为完全等价于 GRID/CONSUME/IMPORT。
 */
@ConfigurationProperties(prefix = "ems.feature.pv")
public record PvFeatureProperties(boolean enabled) {}
