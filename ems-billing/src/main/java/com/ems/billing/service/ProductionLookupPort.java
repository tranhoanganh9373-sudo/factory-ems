package com.ems.billing.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * Billing 视角的产量查询端口。求一段日期内每个组织节点的总产量。
 * 返回的 map 不包含没有产量记录的 org —— 由 service 层解释为 unit_cost = NULL。
 */
public interface ProductionLookupPort {
    Map<Long, BigDecimal> sumByOrgIds(Collection<Long> orgNodeIds, LocalDate from, LocalDate to);
}
