package com.ems.billing.service.impl;

import com.ems.billing.dto.BillDTO;
import com.ems.billing.dto.BillPeriodDTO;
import com.ems.billing.entity.Bill;
import com.ems.billing.entity.BillLine;
import com.ems.billing.entity.BillPeriod;
import com.ems.billing.entity.BillPeriodStatus;
import com.ems.billing.repository.BillLineRepository;
import com.ems.billing.repository.BillPeriodRepository;
import com.ems.billing.dto.CostDistributionDTO;
import com.ems.billing.repository.BillRepository;
import com.ems.billing.service.BillingService;
import com.ems.billing.service.ProductionLookupPort;
import com.ems.audit.annotation.Audited;
import com.ems.cost.entity.RunStatus;
import com.ems.orgtree.dto.OrgNodeDTO;
import com.ems.orgtree.service.OrgNodeService;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.CostAllocationRun;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.repository.CostAllocationLineRepository;
import com.ems.cost.repository.CostAllocationRuleRepository;
import com.ems.cost.repository.CostAllocationRunRepository;
import com.ems.cost.service.FeedInRevenueService;
import com.ems.meter.entity.EnergySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BillingServiceImpl implements BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingServiceImpl.class);
    private static final ZoneOffset Z = ZoneOffset.ofHours(8);

    private final BillPeriodRepository periodRepo;
    private final BillRepository billRepo;
    private final BillLineRepository billLineRepo;
    private final CostAllocationRunRepository runRepo;
    private final CostAllocationLineRepository lineRepo;
    private final CostAllocationRuleRepository ruleRepo;
    private final ProductionLookupPort productionLookup;
    private final OrgNodeService orgNodes;
    private final FeedInRevenueService feedInRevenue;

    public BillingServiceImpl(BillPeriodRepository periodRepo,
                              BillRepository billRepo,
                              BillLineRepository billLineRepo,
                              CostAllocationRunRepository runRepo,
                              CostAllocationLineRepository lineRepo,
                              CostAllocationRuleRepository ruleRepo,
                              ProductionLookupPort productionLookup,
                              OrgNodeService orgNodes,
                              FeedInRevenueService feedInRevenue) {
        this.periodRepo = periodRepo;
        this.billRepo = billRepo;
        this.billLineRepo = billLineRepo;
        this.runRepo = runRepo;
        this.lineRepo = lineRepo;
        this.ruleRepo = ruleRepo;
        this.productionLookup = productionLookup;
        this.orgNodes = orgNodes;
        this.feedInRevenue = feedInRevenue;
    }

    @Override
    @Transactional
    public BillPeriodDTO ensurePeriod(YearMonth ym) {
        String ymStr = ym.toString();
        Optional<BillPeriod> existing = periodRepo.findByYearMonth(ymStr);
        if (existing.isPresent()) return BillPeriodDTO.from(existing.get());
        BillPeriod p = new BillPeriod();
        p.setYearMonth(ymStr);
        p.setPeriodStart(ym.atDay(1).atStartOfDay().atOffset(Z));
        p.setPeriodEnd(ym.plusMonths(1).atDay(1).atStartOfDay().atOffset(Z));
        return BillPeriodDTO.from(periodRepo.save(p));
    }

    @Override
    @Transactional
    @Audited(action = "BILL_GENERATE", resourceType = "BILL_PERIOD", resourceIdExpr = "#periodId",
             summaryExpr = "'generate bills for period id=' + #periodId")
    public BillPeriodDTO generateBills(Long periodId, Long actorUserId) {
        BillPeriod p = periodRepo.findById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("BillPeriod not found: id=" + periodId));
        p.assertWritable();   // LOCKED 拒绝

        // 1) 找完全覆盖该账期的最近一次 SUCCESS run
        List<CostAllocationRun> covering =
                runRepo.findLatestSuccessCovering(p.getPeriodStart(), p.getPeriodEnd());
        if (covering.isEmpty()) {
            throw new IllegalStateException(
                    "No SUCCESS cost_allocation_run covers bill_period " + p.getYearMonth()
                    + " [" + p.getPeriodStart() + ".." + p.getPeriodEnd() + "]");
        }
        CostAllocationRun run = covering.get(0);

        // 2) 取该 run 的全部 lines
        List<CostAllocationLine> lines = lineRepo.findByRunId(run.getId());

        // 3) 重写策略：CLOSED → CLOSED 时先删旧 bill（bill_line 经 FK CASCADE 清理）
        if (p.getStatus() == BillPeriodStatus.CLOSED) {
            int deleted = billRepo.deleteByPeriodId(periodId);
            log.info("regenerate bills for period {}: deleted {} old bill rows", p.getYearMonth(), deleted);
        }

        // 4) GROUP BY (org, energy)
        Map<OrgEnergyKey, List<CostAllocationLine>> grouped = lines.stream()
                .collect(Collectors.groupingBy(l -> new OrgEnergyKey(l.getTargetOrgId(), l.getEnergyType())));

        if (grouped.isEmpty()) {
            log.warn("cost run {} produced 0 lines; bill_period {} closes with no bills",
                    run.getId(), p.getYearMonth());
            p.close(actorUserId);
            return BillPeriodDTO.from(periodRepo.save(p));
        }

        // 5) 查产量：每个 org 在账期内的总产量
        Set<Long> orgIds = grouped.keySet().stream().map(OrgEnergyKey::orgNodeId).collect(Collectors.toSet());
        LocalDate from = p.getPeriodStart().toLocalDate();
        LocalDate to = p.getPeriodEnd().toLocalDate().minusDays(1);
        Map<Long, BigDecimal> productionByOrg = productionLookup.sumByOrgIds(orgIds, from, to);

        // 6) 取所有相关 rule 的 name 用作 source_label
        Set<Long> ruleIds = lines.stream().map(CostAllocationLine::getRuleId).collect(Collectors.toSet());
        Map<Long, String> ruleNameById = new HashMap<>();
        for (CostAllocationRule r : ruleRepo.findAllById(ruleIds)) {
            ruleNameById.put(r.getId(), r.getName());
        }

        // 7) 写 bill + bill_line
        for (Map.Entry<OrgEnergyKey, List<CostAllocationLine>> e : grouped.entrySet()) {
            OrgEnergyKey key = e.getKey();
            List<CostAllocationLine> group = e.getValue();

            BigDecimal qty       = sum(group, CostAllocationLine::getQuantity);
            BigDecimal amt       = sum(group, CostAllocationLine::getAmount);
            BigDecimal sharpAmt  = sum(group, CostAllocationLine::getSharpAmount);
            BigDecimal peakAmt   = sum(group, CostAllocationLine::getPeakAmount);
            BigDecimal flatAmt   = sum(group, CostAllocationLine::getFlatAmount);
            BigDecimal valleyAmt = sum(group, CostAllocationLine::getValleyAmount);

            BigDecimal prodQty = productionByOrg.get(key.orgNodeId);   // null/0 → unit_cost = NULL
            boolean hasProd = prodQty != null && prodQty.compareTo(BigDecimal.ZERO) > 0;
            BigDecimal unitCost      = hasProd ? amt.divide(prodQty, 6, RoundingMode.HALF_UP) : null;
            BigDecimal unitIntensity = hasProd ? qty.divide(prodQty, 6, RoundingMode.HALF_UP) : null;

            Bill b = new Bill();
            b.setPeriodId(periodId);
            b.setRunId(run.getId());
            b.setOrgNodeId(key.orgNodeId);
            b.setEnergyType(key.energyType);
            b.setQuantity(qty);
            b.setAmount(amt);
            b.setSharpAmount(sharpAmt);
            b.setPeakAmount(peakAmt);
            b.setFlatAmount(flatAmt);
            b.setValleyAmount(valleyAmt);
            b.setProductionQty(prodQty);
            b.setUnitCost(unitCost);
            b.setUnitIntensity(unitIntensity);
            // v1.2.0 PV：上网卖电收入抵扣
            EnergySource source = EnergySource.SOLAR;
            BigDecimal revenue = feedInRevenue.computeRevenue(key.orgNodeId, source, from, to);
            b.setFeedInRevenue(revenue);
            b.setNetAmount(amt.subtract(revenue));
            b = billRepo.save(b);

            // GROUP lines by rule_id within this (org, energy) → 一行 bill_line per rule
            Map<Long, List<CostAllocationLine>> byRule = group.stream()
                    .collect(Collectors.groupingBy(CostAllocationLine::getRuleId));
            for (Map.Entry<Long, List<CostAllocationLine>> rg : byRule.entrySet()) {
                BillLine bl = new BillLine();
                bl.setBillId(b.getId());
                bl.setRuleId(rg.getKey());
                bl.setSourceLabel(ruleNameById.getOrDefault(rg.getKey(), "rule#" + rg.getKey()));
                bl.setQuantity(sum(rg.getValue(), CostAllocationLine::getQuantity));
                bl.setAmount(sum(rg.getValue(), CostAllocationLine::getAmount));
                billLineRepo.save(bl);
            }
        }

        p.close(actorUserId);
        return BillPeriodDTO.from(periodRepo.save(p));
    }

    @Override
    @Transactional
    @Audited(action = "LOCK", resourceType = "BILL_PERIOD", resourceIdExpr = "#periodId",
             summaryExpr = "'lock bill_period id=' + #periodId")
    public BillPeriodDTO lockPeriod(Long periodId, Long actorUserId) {
        BillPeriod p = periodRepo.findById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("BillPeriod not found: id=" + periodId));
        p.lock(actorUserId);
        return BillPeriodDTO.from(periodRepo.save(p));
    }

    @Override
    @Transactional
    @Audited(action = "UNLOCK", resourceType = "BILL_PERIOD", resourceIdExpr = "#periodId",
             summaryExpr = "'unlock bill_period id=' + #periodId")
    public BillPeriodDTO unlockPeriod(Long periodId, Long actorUserId) {
        BillPeriod p = periodRepo.findById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("BillPeriod not found: id=" + periodId));
        p.unlock();
        return BillPeriodDTO.from(periodRepo.save(p));
    }

    @Override
    @Transactional(readOnly = true)
    public BillPeriodDTO getPeriod(Long periodId) {
        return BillPeriodDTO.from(periodRepo.findById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("BillPeriod not found: id=" + periodId)));
    }

    @Override
    @Transactional(readOnly = true)
    public BillPeriodDTO getPeriodByYearMonth(String yearMonth) {
        return BillPeriodDTO.from(periodRepo.findByYearMonth(yearMonth)
                .orElseThrow(() -> new IllegalArgumentException("BillPeriod not found: ym=" + yearMonth)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillPeriodDTO> listPeriods() {
        return periodRepo.findAllByOrderByYearMonthDesc().stream()
                .map(BillPeriodDTO::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillDTO> listBills(Long periodId, Long orgNodeId) {
        List<Bill> bills = (orgNodeId == null)
                ? billRepo.findByPeriodId(periodId)
                : billRepo.findByPeriodIdAndOrgNodeId(periodId, orgNodeId);
        return bills.stream().map(BillDTO::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BillDTO getBill(Long billId) {
        return BillDTO.from(billRepo.findById(billId)
                .orElseThrow(() -> new IllegalArgumentException("Bill not found: id=" + billId)));
    }

    @Override
    @Transactional(readOnly = true)
    public CostDistributionDTO costDistribution(YearMonth period) {
        CostAllocationRun run;
        if (period == null) {
            // 任意账期下最新的 SUCCESS run
            run = runRepo.findByStatusOrderByCreatedAtDesc(RunStatus.SUCCESS).stream().findFirst().orElse(null);
        } else {
            OffsetDateTime start = period.atDay(1).atStartOfDay().atOffset(Z);
            OffsetDateTime end = period.plusMonths(1).atDay(1).atStartOfDay().atOffset(Z);
            run = runRepo.findLatestSuccessCovering(start, end).stream().findFirst().orElse(null);
        }
        if (run == null) {
            return new CostDistributionDTO(null, null, BigDecimal.ZERO, List.of());
        }

        List<CostAllocationLine> lines = lineRepo.findByRunId(run.getId());

        // GROUP BY orgNodeId → [qty, amt]
        Map<Long, BigDecimal[]> byOrg = new HashMap<>();
        for (CostAllocationLine l : lines) {
            BigDecimal[] agg = byOrg.computeIfAbsent(l.getTargetOrgId(),
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            agg[0] = agg[0].add(l.getQuantity()  == null ? BigDecimal.ZERO : l.getQuantity());
            agg[1] = agg[1].add(l.getAmount()    == null ? BigDecimal.ZERO : l.getAmount());
        }

        BigDecimal grandTotal = byOrg.values().stream().map(a -> a[1])
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CostDistributionDTO.Item> items = new ArrayList<>(byOrg.size());
        for (Map.Entry<Long, BigDecimal[]> e : byOrg.entrySet()) {
            String name;
            try {
                OrgNodeDTO n = orgNodes.getById(e.getKey());
                name = n.name();
            } catch (Exception ex) {
                name = "Node " + e.getKey();
            }
            double pct = grandTotal.signum() > 0
                    ? e.getValue()[1].divide(grandTotal, 4, RoundingMode.HALF_UP).doubleValue() * 100.0
                    : 0.0;
            items.add(new CostDistributionDTO.Item(e.getKey(), name, e.getValue()[0], e.getValue()[1], pct));
        }
        items.sort(Comparator.comparing(CostDistributionDTO.Item::amount).reversed());

        return new CostDistributionDTO(run.getId(), run.getFinishedAt(), grandTotal, items);
    }

    private static BigDecimal sum(List<CostAllocationLine> lines,
                                  Function<CostAllocationLine, BigDecimal> field) {
        BigDecimal acc = BigDecimal.ZERO;
        for (CostAllocationLine l : lines) {
            BigDecimal v = field.apply(l);
            if (v != null) acc = acc.add(v);
        }
        return acc;
    }

    private record OrgEnergyKey(Long orgNodeId, EnergyTypeCode energyType) {}
}
