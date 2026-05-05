package com.ems.billing.controller;

import com.ems.billing.entity.Bill;
import com.ems.billing.entity.BillPeriod;
import com.ems.billing.repository.BillPeriodRepository;
import com.ems.billing.repository.BillRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/api/v1/cost")
public class SavingsController {

    private final BillRepository billRepo;
    private final BillPeriodRepository periodRepo;

    public SavingsController(BillRepository billRepo, BillPeriodRepository periodRepo) {
        this.billRepo = billRepo;
        this.periodRepo = periodRepo;
    }

    @GetMapping("/savings")
    public SavingsDTO savings(@RequestParam Long orgNodeId,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        OffsetDateTime fromTs = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime toTs = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        List<BillPeriod> periods = periodRepo.findOverlapping(fromTs, toTs);

        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal feedInRevenue = BigDecimal.ZERO;
        for (BillPeriod p : periods) {
            for (Bill b : billRepo.findByPeriodIdAndOrgNodeId(p.getId(), orgNodeId)) {
                amount = amount.add(b.getAmount());
                feedInRevenue = feedInRevenue.add(b.getFeedInRevenue());
            }
        }
        BigDecimal netAmount = amount.subtract(feedInRevenue);
        return new SavingsDTO(amount, feedInRevenue, netAmount);
    }

    public record SavingsDTO(BigDecimal amount, BigDecimal feedInRevenue, BigDecimal netAmount) {}
}
