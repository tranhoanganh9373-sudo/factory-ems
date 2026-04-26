package com.ems.cost.service.impl;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.service.AllocationAlgorithmFactory;
import com.ems.cost.service.AllocationContext;
import com.ems.cost.service.AllocationStrategy;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * COMPOSITE: 顺序跑 weights.steps 里的每个 sub-rule，把它们各自 allocate 出来的
 * CostAllocationLine 合并返回。
 *
 * weights JSONB 结构：
 *   {
 *     "steps": [
 *       { "algorithm":"RESIDUAL",     "sourceMeterId":100, "targetOrgIds":[10],
 *         "weights": { "deductMeterIds":[200,201], "basis":"FIXED", "values":{"10":1.0} } },
 *       { "algorithm":"PROPORTIONAL", "sourceMeterId":300, "targetOrgIds":[20,21],
 *         "weights": { "basis":"AREA" } }
 *     ]
 *   }
 *
 * sub-rule 同期 / 同 energyType（由顶层 rule 驱动）。
 * 设计取舍：MVP 步骤之间彼此独立，不做"上一步残差喂给下一步"的虚拟测点 — 那进 v1.2。
 */
@Component
public class CompositeAllocationStrategy implements AllocationStrategy {

    private final AllocationAlgorithmFactory factory;

    public CompositeAllocationStrategy(@Lazy AllocationAlgorithmFactory factory) {
        this.factory = factory;
    }

    @Override
    public AllocationAlgorithm supports() {
        return AllocationAlgorithm.COMPOSITE;
    }

    @Override
    public List<CostAllocationLine> allocate(CostAllocationRule rule, AllocationContext ctx) {
        List<Map<String, Object>> steps = parseSteps(rule.getWeights());
        if (steps.isEmpty()) {
            throw new IllegalArgumentException(
                    "COMPOSITE rule must have at least 1 step in weights.steps: rule.code=" + rule.getCode());
        }

        List<CostAllocationLine> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            CostAllocationRule subRule = buildSubRule(rule, step, i);
            AllocationAlgorithm subAlgo = subRule.getAlgorithm();
            if (subAlgo == AllocationAlgorithm.COMPOSITE) {
                throw new IllegalArgumentException(
                        "COMPOSITE step " + i + " cannot itself be COMPOSITE: rule.code=" + rule.getCode());
            }
            AllocationStrategy strategy = factory.of(subAlgo);
            List<CostAllocationLine> sublines = strategy.allocate(subRule, ctx);
            // sub-rule has no id; bind every line back to the COMPOSITE rule
            for (CostAllocationLine ln : sublines) {
                ln.setRuleId(rule.getId());
            }
            out.addAll(sublines);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseSteps(Map<String, Object> weights) {
        if (weights == null) return List.of();
        Object raw = weights.get("steps");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private static CostAllocationRule buildSubRule(CostAllocationRule parent, Map<String, Object> step, int idx) {
        CostAllocationRule sub = new CostAllocationRule();
        sub.setCode(parent.getCode() + "/step" + idx);
        sub.setName(parent.getName() + " · step " + idx);
        sub.setEnergyType(parent.getEnergyType());

        Object algo = step.get("algorithm");
        if (algo == null) {
            throw new IllegalArgumentException(
                    "COMPOSITE step " + idx + " missing 'algorithm': rule.code=" + parent.getCode());
        }
        try {
            sub.setAlgorithm(AllocationAlgorithm.valueOf(algo.toString().toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "COMPOSITE step " + idx + " has invalid algorithm '" + algo + "': rule.code=" + parent.getCode(), e);
        }

        Object src = step.get("sourceMeterId");
        if (!(src instanceof Number n)) {
            throw new IllegalArgumentException(
                    "COMPOSITE step " + idx + " missing/invalid 'sourceMeterId': rule.code=" + parent.getCode());
        }
        sub.setSourceMeterId(n.longValue());

        Object tgt = step.get("targetOrgIds");
        if (!(tgt instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(
                    "COMPOSITE step " + idx + " missing/empty 'targetOrgIds': rule.code=" + parent.getCode());
        }
        Long[] orgs = new Long[list.size()];
        for (int j = 0; j < list.size(); j++) {
            Object e = list.get(j);
            orgs[j] = (e instanceof Number num) ? num.longValue() : Long.valueOf(e.toString());
        }
        sub.setTargetOrgIds(orgs);

        @SuppressWarnings("unchecked")
        Map<String, Object> nestedWeights =
                (step.get("weights") instanceof Map<?, ?> mw) ? (Map<String, Object>) mw : Map.of();
        sub.setWeights(nestedWeights);

        // carry parent id so CostAllocationLine.ruleId points at the COMPOSITE itself
        sub.setEnergyType(parent.getEnergyType() == null ? EnergyTypeCode.ELEC : parent.getEnergyType());
        return sub;
    }
}
