package com.ems.collector.config;

/**
 * 寄存器解码后的数值类型。每种类型固定占用的 16-bit 寄存器数量见 {@link #wordCount()}。
 *
 * <p>BIT 是 COIL/DISCRETE_INPUT 的天然类型（1 个布尔位）；其他类型对应 HOLDING/INPUT
 * 寄存器组合。配置时 register.count 必须与此处 wordCount() 匹配（验证器会检查）。
 */
public enum DataType {
    /** 1-bit boolean — for COIL / DISCRETE_INPUT only. */
    BIT(0),
    UINT16(1),
    INT16(1),
    UINT32(2),
    INT32(2),
    FLOAT32(2),
    FLOAT64(4);

    private final int wordCount;

    DataType(int wordCount) {
        this.wordCount = wordCount;
    }

    /** Number of 16-bit Modbus words this data type occupies. BIT returns 0 (n/a for register). */
    public int wordCount() {
        return wordCount;
    }
}
