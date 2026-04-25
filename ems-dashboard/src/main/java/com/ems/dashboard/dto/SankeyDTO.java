package com.ems.dashboard.dto;

import java.util.List;

/**
 * 能流 Sankey 图：节点 + 边。
 * 节点 id 即测点 id 字符串；name 取测点 code+name；energyType 用于前端染色。
 * 边 value 为 source 测点在区间内的累计读数（按 sumByMeter）。
 */
public record SankeyDTO(List<Node> nodes, List<Link> links) {
    public record Node(String id, String name, String energyType, String unit) {}
    public record Link(String source, String target, double value) {}
}
