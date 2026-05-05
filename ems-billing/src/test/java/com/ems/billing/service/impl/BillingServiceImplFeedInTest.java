package com.ems.billing.service.impl;

import com.ems.billing.entity.Bill;
import com.ems.billing.entity.BillPeriod;
import com.ems.billing.repository.BillLineRepository;
import com.ems.billing.repository.BillPeriodRepository;
import com.ems.billing.repository.BillRepository;
import com.ems.billing.service.ProductionLookupPort;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRun;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.entity.RunStatus;
import com.ems.cost.repository.CostAllocationLineRepository;
import com.ems.cost.repository.CostAllocationRuleRepository;
import com.ems.cost.repository.CostAllocationRunRepository;
import com.ems.cost.service.FeedInRevenueService;
import com.ems.meter.entity.EnergySource;
import com.ems.orgtree.service.OrgNodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingServiceImplFeedInTest {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final Long PERIOD_ID = 200L;
    private static final Long ACTOR = 1L;
    private static final OffsetDateTime PSTART = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
    private static final OffsetDateTime PEND   = OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, Z);

    private final BillPeriodRepository periodRepo       = mock(BillPeriodRepository.class);
    private final BillRepository billRepo               = mock(BillRepository.class);
    private final BillLineRepository billLineRepo       = mock(BillLineRepository.class);
    private final CostAllocationRunRepository runRepo   = mock(CostAllocationRunRepository.class);
    private final CostAllocationLineRepository lineRepo = mock(CostAllocationLineRepository.class);
    private final CostAllocationRuleRepository ruleRepo = mock(CostAllocationRuleRepository.class);
    private final ProductionLookupPort productionLookup = mock(ProductionLookupPort.class);
    private final OrgNodeService orgNodes               = mock(OrgNodeService.class);
    private final FeedInRevenueService feedInRevenue    = mock(FeedInRevenueService.class);

    private final BillingServiceImpl service = new BillingServiceImpl(
            periodRepo, billRepo, billLineRepo, runRepo, lineRepo, ruleRepo,
            productionLookup, orgNodes, feedInRevenue);

    @BeforeEach
    void commonStubs() {
        AtomicLong seq = new AtomicLong(2000L);
        when(billRepo.save(any(Bill.class))).thenAnswer(inv -> {
            Bill b = inv.getArgument(0);
            b.setId(seq.getAndIncrement());
            return b;
        });
        when(periodRepo.save(any(BillPeriod.class))).thenAnswer(inv -> inv.getArgument(0));

        BillPeriod p = new BillPeriod();
        p.setId(PERIOD_ID);
        p.setYearMonth("2026-03");
        p.setPeriodStart(PSTART);
        p.setPeriodEnd(PEND);
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(p));

        CostAllocationRun run = new CostAllocationRun();
        run.setId(10L);
        run.setPeriodStart(PSTART);
        run.setPeriodEnd(PEND);
        run.setStatus(RunStatus.SUCCESS);
        when(runRepo.findLatestSuccessCovering(PSTART, PEND)).thenReturn(List.of(run));

        when(ruleRepo.findAllById(any())).thenReturn(List.of());
        when(productionLookup.sumByOrgIds(any(), any(), any())).thenReturn(Map.of());
    }

    private CostAllocationLine elecLine(Long orgId, String amt) {
        CostAllocationLine l = new CostAllocationLine();
        l.setRuleId(1L);
        l.setTargetOrgId(orgId);
        l.setEnergyType(EnergyTypeCode.ELEC);
        l.setQuantity(new BigDecimal("100"));
        l.setAmount(new BigDecimal(amt));
        l.setSharpAmount(BigDecimal.ZERO);
        l.setPeakAmount(BigDecimal.ZERO);
        l.setFlatAmount(new BigDecimal(amt));
        l.setValleyAmount(BigDecimal.ZERO);
        return l;
    }

    @Test
    void generateBills_withPv_setsNetAmountAndFeedInRevenue() {
        when(lineRepo.findByRunId(10L)).thenReturn(List.of(elecLine(1L, "10000.00")));
        when(feedInRevenue.computeRevenue(eq(1L), eq(EnergySource.SOLAR), any(), any()))
                .thenReturn(new BigDecimal("1500.00"));

        service.generateBills(PERIOD_ID, ACTOR);

        ArgumentCaptor<Bill> cap = ArgumentCaptor.forClass(Bill.class);
        verify(billRepo).save(cap.capture());
        Bill bill = cap.getValue();

        assertThat(bill.getFeedInRevenue()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(bill.getNetAmount()).isEqualByComparingTo(new BigDecimal("8500.00"));
    }

    @Test
    void generateBills_noPv_revenueIsZero_netEqualsAmount() {
        when(lineRepo.findByRunId(10L)).thenReturn(List.of(elecLine(1L, "10000.00")));
        when(feedInRevenue.computeRevenue(eq(1L), eq(EnergySource.SOLAR), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        service.generateBills(PERIOD_ID, ACTOR);

        ArgumentCaptor<Bill> cap = ArgumentCaptor.forClass(Bill.class);
        verify(billRepo).save(cap.capture());
        Bill bill = cap.getValue();

        assertThat(bill.getFeedInRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(bill.getNetAmount()).isEqualByComparingTo(new BigDecimal("10000.00"));
    }
}
