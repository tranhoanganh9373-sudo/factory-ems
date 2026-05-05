package com.ems.dashboard.dto;

/**
 * 拓扑一致性自检：每个父表 vs 直接子表合计 的偏差报告。
 *
 * <p>severity 分级（基于 |residualRatio|，residual = parent - Σchildren）：
 *   - OK            : |ratio| ≤ 5%（精度 / 时间对齐噪声）
 *   - INFO          : 5% < ratio ≤ 15%（正残差，未配子表的合理负载，如照明）
 *   - WARN          : ratio > 15%（正残差异常大，子表覆盖不足）
 *   - WARN_NEGATIVE : -15% ≤ ratio < -5%（轻度负残差，子表略超父表，可能时间对齐 / CT 偏差）
 *   - ALARM         : ratio < -15%（重度负残差，CT 倍率错 / 拓扑配错 / 同回路重复测量）
 *
 * <p>residualRatio 在 parentReading == 0 时为 null。
 */
public record TopologyConsistencyDTO(
    Long parentMeterId,
    String parentCode,
    String parentName,
    String energyType,
    String unit,
    Double parentReading,
    Double childrenSum,
    Integer childrenCount,
    Double residual,
    Double residualRatio,
    String severity
) {}
