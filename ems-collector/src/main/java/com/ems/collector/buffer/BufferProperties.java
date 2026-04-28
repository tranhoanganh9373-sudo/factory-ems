package com.ems.collector.buffer;

/**
 * Buffer 配置。绑定到 {@code ems.collector.buffer.*}。
 * 由 {@link com.ems.collector.config.CollectorProperties} 嵌套持有。
 *
 * @param path                 SQLite 文件路径；目录必须存在且可写
 * @param maxRowsPerDevice     单设备 buffer 上限；超出按 FIFO 丢最旧
 * @param ttlDays              超此天数自动清理
 * @param flushIntervalMs      后台 flush task 周期；默认 30s
 */
public record BufferProperties(
        String path,
        Integer maxRowsPerDevice,
        Integer ttlDays,
        Integer flushIntervalMs
) {
    public BufferProperties {
        if (path == null || path.isBlank()) path = "./data/collector-buffer.db";
        if (maxRowsPerDevice == null) maxRowsPerDevice = 100_000;
        if (ttlDays == null) ttlDays = 7;
        if (flushIntervalMs == null) flushIntervalMs = 30_000;
    }
}
