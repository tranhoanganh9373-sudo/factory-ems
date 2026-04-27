package com.ems.collector.config;

/**
 * 寄存器语义。决定如何处理 wrap-around / 求 delta。
 *
 * <ul>
 *   <li>{@link #GAUGE} — 瞬时量（电压 / 功率 / 温度 / 频率）。直接写当前值。</li>
 *   <li>{@link #ACCUMULATOR} — 累计量（电度 / 用水量）。检测 wrap-around，写 _delta 伴随 field。</li>
 *   <li>{@link #COUNTER} — 单调递增计数器（脉冲数）。同 ACCUMULATOR 的处理但语义偏 IT。MVP 与 ACCUMULATOR 同处理，预留枚举。</li>
 * </ul>
 */
public enum RegisterKind {
    GAUGE,
    ACCUMULATOR,
    COUNTER
}
