package com.ems.billing.controller;

import com.ems.audit.aspect.AuditContext;
import com.ems.billing.dto.BillPeriodDTO;
import com.ems.billing.service.BillingService;
import com.ems.core.dto.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

/**
 * 账期 REST.
 * 路径前缀 /api/v1/bills/periods（spec §7.2）。
 * 读：任意已登录用户。写（close/lock/unlock）：FINANCE / ADMIN。unlock：仅 ADMIN。
 * close/lock/unlock 走 @Audited（在 BillingServiceImpl 上的注解）。
 */
@RestController
@RequestMapping("/api/v1/bills/periods")
public class BillPeriodController {

    private final BillingService service;
    private final AuditContext auditContext;

    public BillPeriodController(BillingService service, AuditContext auditContext) {
        this.service = service;
        this.auditContext = auditContext;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<BillPeriodDTO>> list() {
        return Result.ok(service.listPeriods());
    }

    @GetMapping("/{ym}")
    @PreAuthorize("isAuthenticated()")
    public Result<BillPeriodDTO> getByYearMonth(@PathVariable String ym) {
        return Result.ok(service.getPeriodByYearMonth(ym));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<Result<BillPeriodDTO>> ensure(@RequestParam("ym") String ym) {
        BillPeriodDTO dto = service.ensurePeriod(YearMonth.parse(ym));
        return ResponseEntity.status(201).body(Result.ok(dto));
    }

    /** 关账期：触发账单生成 + status 进入 CLOSED。已 LOCKED 时返回 409（IllegalStateException → GlobalExceptionHandler）。 */
    @PutMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public Result<BillPeriodDTO> close(@PathVariable Long id) {
        return Result.ok(service.generateBills(id, auditContext.currentUserId()));
    }

    /** 锁定账期 CLOSED -> LOCKED。 */
    @PutMapping("/{id}/lock")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public Result<BillPeriodDTO> lock(@PathVariable Long id) {
        return Result.ok(service.lockPeriod(id, auditContext.currentUserId()));
    }

    /** 解锁账期 LOCKED -> CLOSED。仅 ADMIN。 */
    @PutMapping("/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<BillPeriodDTO> unlock(@PathVariable Long id) {
        return Result.ok(service.unlockPeriod(id, auditContext.currentUserId()));
    }
}
