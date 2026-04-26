package com.ems.cost.service;

import com.ems.cost.dto.CostRuleDTO;
import com.ems.cost.dto.CreateCostRuleReq;
import com.ems.cost.dto.UpdateCostRuleReq;

import java.util.List;

/**
 * CRUD for cost_allocation_rule. Disabled rules stay queryable; enable flag toggles
 * whether they participate in dryRun/run. Code is immutable after create.
 */
public interface CostRuleService {

    CostRuleDTO create(CreateCostRuleReq req);

    CostRuleDTO update(Long id, UpdateCostRuleReq req);

    /** Hard delete. Caller must ensure no active runs reference the rule. */
    void delete(Long id);

    CostRuleDTO getById(Long id);

    List<CostRuleDTO> list();
}
