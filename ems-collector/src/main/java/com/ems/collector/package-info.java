/**
 * Modbus 数据采集模块 (子项目 1.5)。
 *
 * <p>启动时按 {@code collector.yml} 装配 device pollers，按周期读 Modbus 寄存器，
 * 解码后写入 InfluxDB。看板 / 报表 / 分摊 全链路无感切换数据源。
 *
 * <p>主要包：
 * <ul>
 *   <li>{@code config/} — YAML schema (CollectorProperties / DeviceConfig / RegisterConfig)</li>
 *   <li>{@code transport/} — ModbusMaster 抽象 + TCP/RTU 实现 (j2mod 包装)</li>
 *   <li>{@code codec/} — RegisterDecoder：寄存器字节 → typed value (含 endian + scale)</li>
 *   <li>{@code service/} — CollectorService 调度 + lifecycle</li>
 *   <li>{@code poller/} — DevicePoller (单设备 polling loop + 状态机)</li>
 *   <li>{@code health/} — CollectorHealthIndicator + Micrometer 指标</li>
 *   <li>{@code controller/} — GET /api/v1/collector/status (只读状态)</li>
 * </ul>
 *
 * @see <a href="https://github.com/steveohara/j2mod">j2mod (Apache 2.0)</a>
 */
package com.ems.collector;
