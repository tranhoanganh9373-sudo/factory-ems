package com.ems.collector.config;

/**
 * Modbus 功能码族。寄存器读取的入口。
 * <ul>
 *   <li>HOLDING — FC03 read holding registers (16-bit, R/W)</li>
 *   <li>INPUT — FC04 read input registers (16-bit, R only)</li>
 *   <li>COIL — FC01 read coils (1-bit, R/W)</li>
 *   <li>DISCRETE_INPUT — FC02 read discrete inputs (1-bit, R only)</li>
 * </ul>
 */
public enum FunctionType {
    HOLDING,
    INPUT,
    COIL,
    DISCRETE_INPUT
}
