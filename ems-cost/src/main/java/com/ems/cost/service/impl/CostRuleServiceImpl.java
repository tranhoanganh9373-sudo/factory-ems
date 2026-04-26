package com.ems.cost.service.impl;

import com.ems.cost.dto.CostRuleDTO;
import com.ems.cost.dto.CreateCostRuleReq;
import com.ems.cost.dto.UpdateCostRuleReq;
import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.repository.CostAllocationRuleRepository;
import com.ems.cost.service.CostRuleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CostRuleServiceImpl implements CostRuleService {

    private final CostAllocationRuleRepository repo;

    public CostRuleServiceImpl(CostAllocationRuleRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public CostRuleDTO create(CreateCostRuleReq req) {
        if (repo.existsByCode(req.code())) {
            throw new IllegalArgumentException("Rule code already exists: " + req.code());
        }
        validateEffective(req.effectiveFrom(), req.effectiveTo());

        CostAllocationRule e = new CostAllocationRule();
        e.setCode(req.code());
        e.setName(req.name());
        e.setDescription(req.description());
        e.setEnergyType(req.energyType());
        e.setAlgorithm(req.algorithm());
        e.setSourceMeterId(req.sourceMeterId());
        e.setTargetOrgIds(req.targetOrgIds().toArray(new Long[0]));
        e.setWeights(req.weights() == null ? new HashMap<>() : new HashMap<>(req.weights()));
        e.setPriority(req.priority() == null ? 100 : req.priority());
        e.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        e.setEffectiveFrom(req.effectiveFrom());
        e.setEffectiveTo(req.effectiveTo());
        validateAlgorithmShape(e.getAlgorithm(), e.getWeights());

        return CostRuleDTO.from(repo.save(e));
    }

    @Override
    @Transactional
    public CostRuleDTO update(Long id, UpdateCostRuleReq req) {
        CostAllocationRule e = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: id=" + id));

        if (req.name() != null) e.setName(req.name());
        if (req.description() != null) e.setDescription(req.description());
        if (req.energyType() != null) e.setEnergyType(req.energyType());
        if (req.algorithm() != null) e.setAlgorithm(req.algorithm());
        if (req.sourceMeterId() != null) e.setSourceMeterId(req.sourceMeterId());
        if (req.targetOrgIds() != null) {
            if (req.targetOrgIds().isEmpty()) {
                throw new IllegalArgumentException("targetOrgIds must not be empty");
            }
            e.setTargetOrgIds(req.targetOrgIds().toArray(new Long[0]));
        }
        if (req.weights() != null) e.setWeights(new HashMap<>(req.weights()));
        if (req.priority() != null) e.setPriority(req.priority());
        if (req.enabled() != null) e.setEnabled(req.enabled());
        if (req.effectiveFrom() != null) e.setEffectiveFrom(req.effectiveFrom());
        if (req.effectiveTo() != null) e.setEffectiveTo(req.effectiveTo());

        validateEffective(e.getEffectiveFrom(), e.getEffectiveTo());
        validateAlgorithmShape(e.getAlgorithm(), e.getWeights());

        return CostRuleDTO.from(repo.save(e));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("Rule not found: id=" + id);
        }
        repo.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public CostRuleDTO getById(Long id) {
        return CostRuleDTO.from(repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: id=" + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostRuleDTO> list() {
        return repo.findAll().stream().map(CostRuleDTO::from).toList();
    }

    private static void validateEffective(java.time.LocalDate from, java.time.LocalDate to) {
        if (from == null) throw new IllegalArgumentException("effectiveFrom must be non-null");
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("effectiveTo must not be before effectiveFrom");
        }
    }

    /** Lightweight shape check; deep validation happens in the strategy at run time. */
    private static void validateAlgorithmShape(AllocationAlgorithm alg, Map<String, Object> weights) {
        if (alg == AllocationAlgorithm.COMPOSITE) {
            Object steps = weights == null ? null : weights.get("steps");
            if (!(steps instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalArgumentException("COMPOSITE rule requires non-empty weights.steps[]");
            }
        }
    }
}
