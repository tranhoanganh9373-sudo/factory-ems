package com.ems.collector.poller;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 累加器型 register 的 wrap-around 检测 + delta 计算。每 device 一个独立 tracker（state 不跨设备）。
 *
 * <p>语义：
 * <ul>
 *   <li>首次 observe 返回 null（没有上一周期参考，不发 delta）</li>
 *   <li>后续 observe，若 {@code current >= previous} → delta = current - previous</li>
 *   <li>若 {@code current < previous}（UINT32 翻转 / 仪表清零）：
 *       <code>delta = (UINT32_MAX + 1) × scale - previous + current</code><br/>
 *       即在 raw 整数空间用 spec §5.2 的公式 {@code (max_uint32 - prev) + current + 1}，
 *       然后再乘以 scale 得到工程单位下的增量。</li>
 * </ul>
 *
 * <p>不持久化：进程重启后 first observation 又是 null，丢一个周期的 delta（spec MVP 接受）。
 *
 * <p>线程安全：本类不要求线程安全；DevicePoller 的 {@code pollOnce()} 已 synchronized。
 */
public class AccumulatorTracker {

    /** 2^32 — 用作 UINT32 wrap 时的"翻转一圈"基数。常量裸写避免运行时计算。 */
    static final BigDecimal UINT32_RANGE = new BigDecimal("4294967296");

    private final Map<String, BigDecimal> last = new HashMap<>();

    /**
     * @param tsField  field key（同一 device 内唯一）
     * @param current  当前 polling 周期解码 + scale 后的值
     * @param scale    register 的 scale；null 当作 1
     * @return 增量值；首次 observation 返回 null
     */
    public BigDecimal observe(String tsField, BigDecimal current, BigDecimal scale) {
        BigDecimal prev = last.put(tsField, current);
        if (prev == null) return null;
        BigDecimal delta = current.subtract(prev);
        if (delta.signum() >= 0) {
            return delta;
        }
        // wrap-around 或 counter 清零：在工程单位下补回"翻转一圈"
        BigDecimal s = scale == null ? BigDecimal.ONE : scale;
        return UINT32_RANGE.multiply(s).subtract(prev).add(current);
    }

    /** 清空状态。重启场景或测试用。 */
    public void clear() {
        last.clear();
    }

    /** Visible for testing. */
    public int size() {
        return last.size();
    }
}
