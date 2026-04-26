package com.ems.billing.service;

import com.ems.billing.dto.BillPeriodDTO;
import com.ems.billing.entity.Bill;
import com.ems.billing.entity.BillLine;
import com.ems.billing.entity.BillPeriod;
import com.ems.billing.entity.BillPeriodStatus;
import com.ems.billing.repository.BillLineRepository;
import com.ems.billing.repository.BillPeriodRepository;
import com.ems.billing.repository.BillRepository;
import com.ems.billing.service.impl.BillingServiceImpl;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.CostAllocationRun;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.entity.RunStatus;
import com.ems.billing.dto.CostDistributionDTO;
import com.ems.cost.repository.CostAllocationLineRepository;
import com.ems.cost.repository.CostAllocationRuleRepository;
import com.ems.cost.repository.CostAllocationRunRepository;
import com.ems.orgtree.dto.OrgNodeDTO;
import com.ems.orgtree.service.OrgNodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingServiceImplTest {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final Long PERIOD_ID = 100L;
    private static final Long ACTOR = 42L;
    private static final OffsetDateTime PSTART = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
    private static final OffsetDateTime PEND   = OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, Z);

    private final BillPeriodRepository periodRepo = mock(BillPeriodRepository.class);
    private final BillRepository billRepo = mock(BillRepository.class);
    private final BillLineRepository billLineRepo = mock(BillLineRepository.class);
    private final CostAllocationRunRepository runRepo = mock(CostAllocationRunRepository.class);
    private final CostAllocationLineRepository lineRepo = mock(CostAllocationLineRepository.class);
    private final CostAllocationRuleRepository ruleRepo = mock(CostAllocationRuleRepository.class);
    private final ProductionLookupPort productionLookup = mock(ProductionLookupPort.class);
    private final OrgNodeService orgNodes = mock(OrgNodeService.class);

    private final BillingServiceImpl service = new BillingServiceImpl(
            periodRepo, billRepo, billLineRepo, runRepo, lineRepo, ruleRepo, productionLookup, orgNodes);

    private BillPeriod openPeriod() {
        BillPeriod p = new BillPeriod();
        p.setId(PERIOD_ID);
        p.setYearMonth("2026-03");
        p.setPeriodStart(PSTART);
        p.setPeriodEnd(PEND);
        return p;
    }

    private BillPeriod closedPeriod() {
        BillPeriod p = openPeriod();
        p.close(7L);
        return p;
    }

    private BillPeriod lockedPeriod() {
        BillPeriod p = closedPeriod();
        p.lock(7L);
        return p;
    }

    private CostAllocationRun successRun(Long id) {
        CostAllocationRun r = new CostAllocationRun();
        r.setId(id);
        r.setPeriodStart(PSTART);
        r.setPeriodEnd(PEND);
        r.setStatus(RunStatus.SUCCESS);
        return r;
    }

    private CostAllocationLine line(Long ruleId, Long orgId, EnergyTypeCode energy,
                                    String qty, String amt, String[] bands) {
        // bands = {sharp, peak, flat, valley} amounts
        CostAllocationLine l = new CostAllocationLine();
        l.setRuleId(ruleId);
        l.setTargetOrgId(orgId);
        l.setEnergyType(energy);
        l.setQuantity(new BigDecimal(qty));
        l.setAmount(new BigDecimal(amt));
        l.setSharpAmount(new BigDecimal(bands[0]));
        l.setPeakAmount(new BigDecimal(bands[1]));
        l.setFlatAmount(new BigDecimal(bands[2]));
        l.setValleyAmount(new BigDecimal(bands[3]));
        return l;
    }

    private CostAllocationRule rule(Long id, String name) {
        // 匿名子类覆写 getter — 避开 Mockito stub 在外层 when().thenReturn() 内触发 UnfinishedStubbingException。
        return new CostAllocationRule() {
            @Override public Long getId() { return id; }
            @Override public String getName() { return name; }
        };
    }

    @BeforeEach
    void wireBillSaveToAssignIds() {
        AtomicLong seq = new AtomicLong(1000L);
        when(billRepo.save(any(Bill.class))).thenAnswer(inv -> {
            Bill b = inv.getArgument(0);
            b.setId(seq.getAndIncrement());
            return b;
        });
        when(periodRepo.save(any(BillPeriod.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------- generateBills --------------------

    @Test
    void generateBills_locked_period_throws() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(lockedPeriod()));

        assertThatThrownBy(() -> service.generateBills(PERIOD_ID, ACTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCKED");

        verify(runRepo, never()).findLatestSuccessCovering(any(), any());
        verify(billRepo, never()).save(any());
    }

    @Test
    void generateBills_no_covering_run_throws() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(openPeriod()));
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of());

        assertThatThrownBy(() -> service.generateBills(PERIOD_ID, ACTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No SUCCESS cost_allocation_run covers");
    }

    @Test
    void generateBills_no_lines_closes_period_with_zero_bills() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(openPeriod()));
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of(successRun(7L)));
        when(lineRepo.findByRunId(7L)).thenReturn(List.of());

        BillPeriodDTO out = service.generateBills(PERIOD_ID, ACTOR);

        assertThat(out.status()).isEqualTo(BillPeriodStatus.CLOSED);
        verify(billRepo, never()).save(any());
        verify(billLineRepo, never()).save(any());
    }

    @Test
    void generateBills_aggregates_by_org_and_energy() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(openPeriod()));
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of(successRun(7L)));
        // 两条 rule，都打到 org=50, ELEC：(qty=10, amt=30) + (qty=20, amt=60) → 期望聚合 qty=30, amt=90
        when(lineRepo.findByRunId(7L)).thenReturn(List.of(
                line(1L, 50L, EnergyTypeCode.ELEC, "10", "30", new String[]{"5", "10", "10", "5"}),
                line(2L, 50L, EnergyTypeCode.ELEC, "20", "60", new String[]{"10", "20", "20", "10"})
        ));
        when(ruleRepo.findAllById(any())).thenReturn(List.of(rule(1L, "R1"), rule(2L, "R2")));
        when(productionLookup.sumByOrgIds(any(), any(), any())).thenReturn(Map.of());

        service.generateBills(PERIOD_ID, ACTOR);

        ArgumentCaptor<Bill> billCap = ArgumentCaptor.forClass(Bill.class);
        verify(billRepo, times(1)).save(billCap.capture());
        Bill b = billCap.getValue();
        assertThat(b.getOrgNodeId()).isEqualTo(50L);
        assertThat(b.getEnergyType()).isEqualTo(EnergyTypeCode.ELEC);
        assertThat(b.getQuantity()).isEqualByComparingTo("30");
        assertThat(b.getAmount()).isEqualByComparingTo("90");
        assertThat(b.getSharpAmount()).isEqualByComparingTo("15");
        assertThat(b.getPeakAmount()).isEqualByComparingTo("30");
        assertThat(b.getFlatAmount()).isEqualByComparingTo("30");
        assertThat(b.getValleyAmount()).isEqualByComparingTo("15");
    }

    @Test
    void generateBills_separate_org_or_energy_creates_separate_bills() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(openPeriod()));
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of(successRun(7L)));
        when(lineRepo.findByRunId(7L)).thenReturn(List.of(
                line(1L, 50L, EnergyTypeCode.ELEC,  "10", "30", new String[]{"0","0","30","0"}),
                line(1L, 50L, EnergyTypeCode.WATER, "5",  "8",  new String[]{"0","0","0","0"}),
                line(1L, 51L, EnergyTypeCode.ELEC,  "12", "36", new String[]{"0","0","36","0"})
        ));
        when(ruleRepo.findAllById(any())).thenReturn(List.of(rule(1L, "R1")));
        when(productionLookup.sumByOrgIds(any(), any(), any())).thenReturn(Map.of());

        service.generateBills(PERIOD_ID, ACTOR);

        verify(billRepo, times(3)).save(any(Bill.class));   // 3 个 (org, energy) 组合
    }

    @Test
    void generateBills_unit_cost_computed_when_production_present() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(openPeriod()));
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of(successRun(7L)));
        when(lineRepo.findByRunId(7L)).thenReturn(List.of(
                line(1L, 50L, EnergyTypeCode.ELEC, "100", "300", new String[]{"0","0","300","0"})
        ));
        when(ruleRepo.findAllById(any())).thenReturn(List.of(rule(1L, "R1")));
        Map<Long, BigDecimal> prod = new HashMap<>();
        prod.put(50L, new BigDecimal("60"));   // 60 件产量
        when(productionLookup.sumByOrgIds(any(), any(), any())).thenReturn(prod);

        service.generateBills(PERIOD_ID, ACTOR);

        ArgumentCaptor<Bill> billCap = ArgumentCaptor.forClass(Bill.class);
        verify(billRepo).save(billCap.capture());
        Bill b = billCap.getValue();
        assertThat(b.getProductionQty()).isEqualByComparingTo("60");
        assertThat(b.getUnitCost()).isEqualByComparingTo("5.000000");        // 300 / 60
        assertThat(b.getUnitIntensity()).isEqualByComparingTo("1.666667");   // 100 / 60
    }

    @Test
    void generateBills_unit_cost_null_when_no_production() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(openPeriod()));
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of(successRun(7L)));
        when(lineRepo.findByRunId(7L)).thenReturn(List.of(
                line(1L, 50L, EnergyTypeCode.ELEC, "10", "30", new String[]{"0","0","30","0"})
        ));
        when(ruleRepo.findAllById(any())).thenReturn(List.of(rule(1L, "R1")));
        when(productionLookup.sumByOrgIds(any(), any(), any())).thenReturn(Map.of());

        service.generateBills(PERIOD_ID, ACTOR);

        ArgumentCaptor<Bill> billCap = ArgumentCaptor.forClass(Bill.class);
        verify(billRepo).save(billCap.capture());
        Bill b = billCap.getValue();
        assertThat(b.getProductionQty()).isNull();
        assertThat(b.getUnitCost()).isNull();
        assertThat(b.getUnitIntensity()).isNull();
    }

    @Test
    void generateBills_unit_cost_null_when_production_zero() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(openPeriod()));
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of(successRun(7L)));
        when(lineRepo.findByRunId(7L)).thenReturn(List.of(
                line(1L, 50L, EnergyTypeCode.ELEC, "10", "30", new String[]{"0","0","30","0"})
        ));
        when(ruleRepo.findAllById(any())).thenReturn(List.of(rule(1L, "R1")));
        Map<Long, BigDecimal> prod = new HashMap<>();
        prod.put(50L, BigDecimal.ZERO);
        when(productionLookup.sumByOrgIds(any(), any(), any())).thenReturn(prod);

        service.generateBills(PERIOD_ID, ACTOR);

        ArgumentCaptor<Bill> billCap = ArgumentCaptor.forClass(Bill.class);
        verify(billRepo).save(billCap.capture());
        Bill b = billCap.getValue();
        assertThat(b.getProductionQty()).isEqualByComparingTo("0");
        assertThat(b.getUnitCost()).isNull();
        assertThat(b.getUnitIntensity()).isNull();
    }

    @Test
    void generateBills_re_close_deletes_old_bills_first() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(closedPeriod()));   // 已 CLOSED
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of(successRun(7L)));
        when(lineRepo.findByRunId(7L)).thenReturn(List.of(
                line(1L, 50L, EnergyTypeCode.ELEC, "10", "30", new String[]{"0","0","30","0"})
        ));
        when(ruleRepo.findAllById(any())).thenReturn(List.of(rule(1L, "R1")));
        when(productionLookup.sumByOrgIds(any(), any(), any())).thenReturn(Map.of());

        service.generateBills(PERIOD_ID, ACTOR);

        verify(billRepo).deleteByPeriodId(PERIOD_ID);
    }

    @Test
    void generateBills_first_close_does_not_delete() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(openPeriod()));   // OPEN
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of(successRun(7L)));
        when(lineRepo.findByRunId(7L)).thenReturn(List.of(
                line(1L, 50L, EnergyTypeCode.ELEC, "10", "30", new String[]{"0","0","30","0"})
        ));
        when(ruleRepo.findAllById(any())).thenReturn(List.of(rule(1L, "R1")));
        when(productionLookup.sumByOrgIds(any(), any(), any())).thenReturn(Map.of());

        service.generateBills(PERIOD_ID, ACTOR);

        verify(billRepo, never()).deleteByPeriodId(anyLong());
    }

    @Test
    void generateBills_creates_bill_line_per_rule_within_bill() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(openPeriod()));
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of(successRun(7L)));
        // 同一 (org=50, ELEC)，两条不同的 rule
        when(lineRepo.findByRunId(7L)).thenReturn(List.of(
                line(1L, 50L, EnergyTypeCode.ELEC, "10", "30", new String[]{"0","0","30","0"}),
                line(2L, 50L, EnergyTypeCode.ELEC, "20", "60", new String[]{"0","0","60","0"})
        ));
        when(ruleRepo.findAllById(any())).thenReturn(List.of(
                rule(1L, "1#变压器残差"),
                rule(2L, "2#变压器直接归集")));
        when(productionLookup.sumByOrgIds(any(), any(), any())).thenReturn(Map.of());

        service.generateBills(PERIOD_ID, ACTOR);

        ArgumentCaptor<BillLine> lineCap = ArgumentCaptor.forClass(BillLine.class);
        verify(billLineRepo, times(2)).save(lineCap.capture());
        List<BillLine> saved = lineCap.getAllValues();
        assertThat(saved).extracting(BillLine::getRuleId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(saved).extracting(BillLine::getSourceLabel)
                .containsExactlyInAnyOrder("1#变压器残差", "2#变压器直接归集");
    }

    @Test
    void generateBills_marks_period_CLOSED() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(openPeriod()));
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of(successRun(7L)));
        when(lineRepo.findByRunId(7L)).thenReturn(List.of(
                line(1L, 50L, EnergyTypeCode.ELEC, "10", "30", new String[]{"0","0","30","0"})
        ));
        when(ruleRepo.findAllById(any())).thenReturn(List.of(rule(1L, "R1")));
        when(productionLookup.sumByOrgIds(any(), any(), any())).thenReturn(Map.of());

        BillPeriodDTO out = service.generateBills(PERIOD_ID, ACTOR);

        assertThat(out.status()).isEqualTo(BillPeriodStatus.CLOSED);
        assertThat(out.closedBy()).isEqualTo(ACTOR);
    }

    // -------------------- lock / unlock --------------------

    @Test
    void lockPeriod_transitions_CLOSED_to_LOCKED() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(closedPeriod()));

        BillPeriodDTO out = service.lockPeriod(PERIOD_ID, ACTOR);

        assertThat(out.status()).isEqualTo(BillPeriodStatus.LOCKED);
        assertThat(out.lockedBy()).isEqualTo(ACTOR);
    }

    @Test
    void unlockPeriod_transitions_LOCKED_to_CLOSED() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(lockedPeriod()));

        BillPeriodDTO out = service.unlockPeriod(PERIOD_ID, ACTOR);

        assertThat(out.status()).isEqualTo(BillPeriodStatus.CLOSED);
        assertThat(out.lockedBy()).isNull();
    }

    // -------------------- ensurePeriod --------------------

    @Test
    void ensurePeriod_returns_existing_when_present() {
        BillPeriod existing = openPeriod();
        when(periodRepo.findByYearMonth("2026-03")).thenReturn(Optional.of(existing));

        BillPeriodDTO out = service.ensurePeriod(YearMonth.of(2026, 3));

        assertThat(out.id()).isEqualTo(PERIOD_ID);
        verify(periodRepo, never()).save(any(BillPeriod.class));
    }

    @Test
    void ensurePeriod_creates_new_when_missing() {
        when(periodRepo.findByYearMonth("2026-03")).thenReturn(Optional.empty());
        when(periodRepo.save(any(BillPeriod.class))).thenAnswer(inv -> {
            BillPeriod p = inv.getArgument(0);
            p.setId(999L);
            return p;
        });

        BillPeriodDTO out = service.ensurePeriod(YearMonth.of(2026, 3));

        assertThat(out.yearMonth()).isEqualTo("2026-03");
        assertThat(out.status()).isEqualTo(BillPeriodStatus.OPEN);
        ArgumentCaptor<BillPeriod> cap = ArgumentCaptor.forClass(BillPeriod.class);
        verify(periodRepo).save(cap.capture());
        assertThat(cap.getValue().getPeriodStart()).isEqualTo(PSTART);
        assertThat(cap.getValue().getPeriodEnd()).isEqualTo(PEND);
    }

    @Test
    void getPeriod_throws_when_missing() {
        when(periodRepo.findById(eq(999L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPeriod(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BillPeriod not found");
    }

    // -------------------- costDistribution (Phase K) --------------------

    @Test
    void costDistribution_no_period_uses_latest_SUCCESS_run() {
        CostAllocationRun run = successRun(7L);
        run.setFinishedAt(OffsetDateTime.now(Z));
        when(runRepo.findByStatusOrderByCreatedAtDesc(RunStatus.SUCCESS)).thenReturn(List.of(run));
        when(lineRepo.findByRunId(7L)).thenReturn(List.of(
                line(1L, 50L, EnergyTypeCode.ELEC, "10", "30", new String[]{"0","0","30","0"}),
                line(1L, 51L, EnergyTypeCode.ELEC, "20", "60", new String[]{"0","0","60","0"})
        ));
        when(orgNodes.getById(50L)).thenReturn(orgDto(50L, "一车间"));
        when(orgNodes.getById(51L)).thenReturn(orgDto(51L, "二车间"));

        CostDistributionDTO out = service.costDistribution(null);

        assertThat(out.runId()).isEqualTo(7L);
        assertThat(out.totalAmount()).isEqualByComparingTo("90");
        assertThat(out.items()).hasSize(2);
        // 排序：amount desc → 二车间 60 在前，一车间 30 在后
        assertThat(out.items().get(0).orgNodeId()).isEqualTo(51L);
        assertThat(out.items().get(0).amount()).isEqualByComparingTo("60");
        assertThat(out.items().get(0).percent()).isCloseTo(66.67, within(0.1));
        assertThat(out.items().get(1).orgNodeId()).isEqualTo(50L);
        assertThat(out.items().get(1).percent()).isCloseTo(33.33, within(0.1));
    }

    @Test
    void costDistribution_with_period_uses_covering_run() {
        CostAllocationRun run = successRun(8L);
        run.setFinishedAt(OffsetDateTime.now(Z));
        when(runRepo.findLatestSuccessCovering(any(), any())).thenReturn(List.of(run));
        when(lineRepo.findByRunId(8L)).thenReturn(List.of(
                line(1L, 50L, EnergyTypeCode.ELEC, "10", "30", new String[]{"0","0","30","0"})
        ));
        when(orgNodes.getById(50L)).thenReturn(orgDto(50L, "一车间"));

        CostDistributionDTO out = service.costDistribution(java.time.YearMonth.of(2026, 3));

        assertThat(out.runId()).isEqualTo(8L);
        verify(runRepo).findLatestSuccessCovering(any(), any());
    }

    @Test
    void costDistribution_no_run_returns_empty() {
        when(runRepo.findByStatusOrderByCreatedAtDesc(RunStatus.SUCCESS)).thenReturn(List.of());

        CostDistributionDTO out = service.costDistribution(null);

        assertThat(out.runId()).isNull();
        assertThat(out.totalAmount()).isEqualByComparingTo("0");
        assertThat(out.items()).isEmpty();
    }

    @Test
    void costDistribution_falls_back_when_org_lookup_fails() {
        CostAllocationRun run = successRun(7L);
        when(runRepo.findByStatusOrderByCreatedAtDesc(RunStatus.SUCCESS)).thenReturn(List.of(run));
        when(lineRepo.findByRunId(7L)).thenReturn(List.of(
                line(1L, 99L, EnergyTypeCode.ELEC, "10", "30", new String[]{"0","0","30","0"})
        ));
        when(orgNodes.getById(99L)).thenThrow(new RuntimeException("vanished"));

        CostDistributionDTO out = service.costDistribution(null);

        assertThat(out.items()).hasSize(1);
        assertThat(out.items().get(0).orgName()).isEqualTo("Node 99");
    }

    private OrgNodeDTO orgDto(Long id, String name) {
        return new OrgNodeDTO(id, null, name, "ORG-" + id, "DEPT", 0,
                OffsetDateTime.now(Z), List.of());
    }
}
