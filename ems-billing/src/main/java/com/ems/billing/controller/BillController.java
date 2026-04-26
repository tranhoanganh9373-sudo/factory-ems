package com.ems.billing.controller;

import com.ems.billing.dto.BillDTO;
import com.ems.billing.dto.BillLineDTO;
import com.ems.billing.repository.BillLineRepository;
import com.ems.billing.service.BillingService;
import com.ems.core.dto.Result;
import com.ems.cost.entity.EnergyTypeCode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 账单 REST.
 * 读权限：所有已登录用户。
 * 真正的 org-scope 子树过滤（FINANCE/ADMIN 全部 viewable，VIEWER 仅 viewable 子树）等到 OrgScopeFilter 中央化后接入；
 * 当前调用方需显式传 orgNodeId（与子项目 1 的报表查询模式一致）。
 */
@RestController
@RequestMapping("/api/v1/bills")
public class BillController {

    private final BillingService service;
    private final BillLineRepository billLineRepo;

    public BillController(BillingService service, BillLineRepository billLineRepo) {
        this.service = service;
        this.billLineRepo = billLineRepo;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<BillDTO>> list(@RequestParam("periodId") Long periodId,
                                      @RequestParam(value = "orgNodeId", required = false) Long orgNodeId,
                                      @RequestParam(value = "energyType", required = false) EnergyTypeCode energyType) {
        List<BillDTO> bills = service.listBills(periodId, orgNodeId);
        if (energyType != null) {
            bills = bills.stream().filter(b -> b.energyType() == energyType).toList();
        }
        return Result.ok(bills);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<BillDTO> getById(@PathVariable Long id) {
        return Result.ok(service.getBill(id));
    }

    @GetMapping("/{id}/lines")
    @PreAuthorize("isAuthenticated()")
    public Result<List<BillLineDTO>> getLines(@PathVariable Long id) {
        // ensure bill exists (404 if not)
        service.getBill(id);
        List<BillLineDTO> lines = billLineRepo.findByBillId(id).stream()
                .map(BillLineDTO::from).toList();
        return Result.ok(lines);
    }
}
