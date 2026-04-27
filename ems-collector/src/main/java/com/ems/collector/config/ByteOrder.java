package com.ems.collector.config;

/**
 * 多字节寄存器拼装顺序（仅对 UINT32/INT32/FLOAT32/FLOAT64 有效；UINT16/INT16/BIT 忽略此字段）。
 *
 * <p>给定 4 字节 value 大端表示 [A, B, C, D]：
 * <ul>
 *   <li>{@link #ABCD} — 大端字 + 大端字节（标准 IEEE，多数西门子/施耐德）</li>
 *   <li>{@link #CDAB} — word swap（许多国产电表如华立 / 德力西）</li>
 *   <li>{@link #BADC} — byte swap within word</li>
 *   <li>{@link #DCBA} — 小端（少见）</li>
 * </ul>
 *
 * <p>FLOAT64 (8 字节) 的拼装规则在 RegisterDecoder 内按 ABCD 含义扩展（先按 word 反序再按 byte 反序）。
 */
public enum ByteOrder {
    ABCD,
    CDAB,
    BADC,
    DCBA
}
