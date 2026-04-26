package com.ems.cost.service;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;

import java.util.List;

/**
 * 4 种分摊算法的统一签名。
 * 算法只产出 line（带 4 段拆分），不持久化、不写 run 状态——这些由 CostAllocationService 负责。
 */
public interface AllocationStrategy {

    /**
     * 此 strategy 处理的 algorithm enum。Factory 用它注册分发。
     */
    AllocationAlgorithm supports();

    /**
     * 计算一条 rule 的分摊结果。
     * 返回的 line 已经填好 quantity / amount / 4 段拆分；id / runId 由 caller 填。
     * 不能修改 ctx；不能持久化。
     */
    List<CostAllocationLine> allocate(CostAllocationRule rule, AllocationContext ctx);
}
