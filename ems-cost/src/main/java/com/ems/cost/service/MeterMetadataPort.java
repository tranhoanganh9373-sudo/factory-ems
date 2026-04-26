package com.ems.cost.service;

/**
 * 给算法层用的最小测点元数据端口。
 * 不暴露 ems-meter 的实体，避免循环依赖逻辑混乱。
 */
public interface MeterMetadataPort {

    /**
     * 返回 meter.energy_type_id；用于 tariffLookup.batch 查电价方案。
     * 找不到测点抛 IllegalArgumentException（"配置错误，规则引用的 source_meter 不存在"）。
     */
    Long energyTypeIdOf(Long meterId);
}
