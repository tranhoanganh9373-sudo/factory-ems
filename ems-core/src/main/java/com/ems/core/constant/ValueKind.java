package com.ems.core.constant;

/**
 * 测点样本的物理含义。决定时序查询如何聚合：
 *
 * <ul>
 *   <li>{@link #INTERVAL_DELTA} — 每个 sample 是一个采样周期内的能耗增量（kWh / cycle）。
 *       区间总量 = sum()。这是历史默认，所有 V2.4.0 之前的存量 meter 都按此处理。</li>
 *   <li>{@link #CUMULATIVE_ENERGY} — 每个 sample 是表底数（kWh，单调递增）。区间总量 = last - first，
 *       跨桶时按桶 difference 再 sum。对应安科瑞表 0x003F 这种"吸收有功总电能"寄存器。</li>
 *   <li>{@link #INSTANT_POWER} — 每个 sample 是瞬时功率。区间总量 = ∫P dt（integral 算子）。
 *       对应安科瑞表 0x0031 这种"总有功功率 P总"寄存器。
 *       <b>单位约定</b>：collector 写入时必须把 value 列的单位统一为 <b>kW</b>（输出才是 kWh）；
 *       如表计原始寄存器是 0.1W、0.001kW 等，须在 collector 的 scale 参数里换算到 kW，
 *       否则区间合计会偏差 10x / 1000x 等量级。</li>
 * </ul>
 *
 * <p>精度说明：
 * <ul>
 *   <li>INTERVAL_DELTA：精度由 collector 决定；漏采会丢能耗。</li>
 *   <li>CUMULATIVE_ENERGY：表计内部硬件累加，最稳定；但区间内换表/计数器归零会使
 *       difference(nonNegative=true) 丢掉负差，导致那段能耗丢失。</li>
 *   <li>INSTANT_POWER：5s 轮询的工业负载误差 0.1%-0.5%；秒级突变负载会丢峰值。
 *       collector 宕机期间被假设为线性插值，长 gap 会偏差较大。</li>
 * </ul>
 *
 * <p>放在 ems-core，因为 ems-meter（实体属性）和 ems-timeseries（MeterRef + 查询分派）都需要引用，
 * 二者之间没有直接依赖。
 */
public enum ValueKind {
    INTERVAL_DELTA,
    CUMULATIVE_ENERGY,
    INSTANT_POWER
}
