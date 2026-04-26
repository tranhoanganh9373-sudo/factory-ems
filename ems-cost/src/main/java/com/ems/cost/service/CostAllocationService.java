package com.ems.cost.service;

import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.CostAllocationRun;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 成本分摊服务 顶层接口。
 *
 * MVP 范围 (Plan 2.1 Phase I)：dryRun 同步预览（不落库）。
 * 后续 Phase J 加 run() 异步 + SUPERSEDED 老 run。
 */
public interface CostAllocationService {

    /**
     * 单条规则同步预览。返回的 CostAllocationLine 没有 id / runId（dry-run 不创建 run）。
     * 调用方拿来 JSON 序列化展示给用户预览。
     *
     * @throws IllegalArgumentException 当 ruleId 不存在或 disabled 或不在 effective_from..effective_to
     */
    List<CostAllocationLine> dryRun(Long ruleId, OffsetDateTime periodStart, OffsetDateTime periodEnd);

    /**
     * 批量预览：当前 active + 在期 的所有规则跑一遍。
     * 用于"如果点 run 现在会得到什么"的整体预览。
     */
    List<CostAllocationLine> dryRunAll(OffsetDateTime periodStart, OffsetDateTime periodEnd);

    /** 不直接持久化的工具方法：跑一条规则。供 run() 复用。 */
    List<CostAllocationLine> runOne(CostAllocationRule rule, OffsetDateTime periodStart, OffsetDateTime periodEnd);

    /**
     * 异步触发分摊运行。立即返回 runId，状态 PENDING；worker 线程负责跑完转 SUCCESS / FAILED。
     *
     * @param ruleIds null 或空 = 跑当期全部 active 规则；非空 = 仅跑指定规则
     * @param createdBy 触发人 user id（可为 null = 系统调度）
     * @return 新建的 run 的 id
     */
    Long submitRun(OffsetDateTime periodStart,
                   OffsetDateTime periodEnd,
                   List<Long> ruleIds,
                   Long createdBy);

    /**
     * 同步执行已存在的 PENDING run（worker 入口；不要在 web 线程直接调用）。
     * 完整流程：PENDING → RUNNING → 执行所有规则 → 持久化 lines → SUCCESS + 老 run SUPERSEDED；
     * 任意环节抛错 → FAILED + errorMessage。
     */
    void executeRun(Long runId);

    /** Read a run by id; throws IllegalArgumentException if missing. */
    CostAllocationRun getRun(Long runId);

    /** All persisted lines for a run, optionally filtered by target org node id. */
    List<CostAllocationLine> getLines(Long runId, Long targetOrgId);
}
