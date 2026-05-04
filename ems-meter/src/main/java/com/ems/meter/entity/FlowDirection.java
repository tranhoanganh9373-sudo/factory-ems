package com.ems.meter.entity;

/**
 * 测点流向。BIDIRECTIONAL 并网点物理上拆成两条 meter（一条 IMPORT、一条 EXPORT），
 * 通过 meter_topology 挂在同一虚拟父节点下，故枚举仅两值。
 */
public enum FlowDirection {
    IMPORT,
    EXPORT
}
