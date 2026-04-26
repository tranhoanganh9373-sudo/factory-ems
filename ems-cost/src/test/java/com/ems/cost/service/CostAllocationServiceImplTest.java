package com.ems.cost.service;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.repository.CostAllocationLineRepository;
import com.ems.cost.repository.CostAllocationRuleRepository;
import com.ems.cost.repository.CostAllocationRunRepository;
import com.ems.cost.service.impl.CostAllocationServiceImpl;
import com.ems.cost.service.impl.DirectAllocationStrategy;
import com.ems.tariff.service.HourPrice;
import com.ems.tariff.service.TariffPriceLookupService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CostAllocationServiceImplTest {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
    private static final OffsetDateTime END   = START.plusHours(3);

    private final CostAllocationRuleRepository ruleRepository = mock(CostAllocationRuleRepository.class);
    private final CostAllocationRunRepository runRepository = mock(CostAllocationRunRepository.class);
    private final CostAllocationLineRepository lineRepository = mock(CostAllocationLineRepository.class);
    private final MeterUsageReader meterUsage = mock(MeterUsageReader.class);
    private final TariffPriceLookupService tariffLookup = mock(TariffPriceLookupService.class);
    private final MeterMetadataPort meterMetadata = mock(MeterMetadataPort.class);
    private final Executor executor = Runnable::run; // synchronous executor for dry-run tests (unused)
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private final DirectAllocationStrategy direct = new DirectAllocationStrategy();
    private final AllocationAlgorithmFactory factory = new AllocationAlgorithmFactory(List.of(direct));
    private final CostAllocationServiceImpl service =
            new CostAllocationServiceImpl(ruleRepository, runRepository, lineRepository, factory,
                    meterUsage, tariffLookup, meterMetadata, executor, txManager);

    private CostAllocationRule directRule(Long id) {
        CostAllocationRule r = new CostAllocationRule();
        r.setCode("R-DIRECT-" + id);
        r.setName("test direct");
        r.setEnergyType(EnergyTypeCode.ELEC);
        r.setAlgorithm(AllocationAlgorithm.DIRECT);
        r.setSourceMeterId(100L);
        r.setTargetOrgIds(new Long[]{50L});
        r.setEnabled(true);
        r.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        return r;
    }

    private void wireMeterAndTariff() {
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START, new BigDecimal("12"))));
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(List.of(
                new HourPrice(START, "FLAT", new BigDecimal("0.50"))));
    }

    @Test
    void dryRun_runs_strategy_and_returns_unpersisted_lines() {
        wireMeterAndTariff();
        when(ruleRepository.findById(1L)).thenReturn(Optional.of(directRule(1L)));

        List<CostAllocationLine> lines = service.dryRun(1L, START, END);

        assertThat(lines).hasSize(1);
        CostAllocationLine line = lines.get(0);
        assertThat(line.getId()).isNull();         // not persisted
        assertThat(line.getRunId()).isNull();      // dry-run has no run
        assertThat(line.getQuantity()).isEqualByComparingTo("12.0000");
        assertThat(line.getAmount()).isEqualByComparingTo("6.0000");
    }

    @Test
    void dryRun_throws_when_rule_not_found() {
        when(ruleRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.dryRun(99L, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rule not found");
    }

    @Test
    void dryRun_throws_when_rule_disabled() {
        CostAllocationRule r = directRule(1L);
        r.setEnabled(false);
        when(ruleRepository.findById(1L)).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.dryRun(1L, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void dryRun_throws_when_rule_not_effective_at_period_start() {
        CostAllocationRule r = directRule(1L);
        r.setEffectiveFrom(LocalDate.of(2026, 6, 1));
        when(ruleRepository.findById(1L)).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.dryRun(1L, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not effective");
    }

    @Test
    void dryRun_throws_when_period_invalid() {
        assertThatThrownBy(() -> service.dryRun(1L, null, END))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.dryRun(1L, END, START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("periodEnd must be after");
    }

    @Test
    void dryRunAll_aggregates_lines_from_all_active_rules() {
        wireMeterAndTariff();
        CostAllocationRule r1 = directRule(1L);
        CostAllocationRule r2 = directRule(2L);
        r2.setTargetOrgIds(new Long[]{60L});
        when(ruleRepository.findAllActive(any())).thenReturn(List.of(r1, r2));

        List<CostAllocationLine> lines = service.dryRunAll(START, END);

        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(CostAllocationLine::getTargetOrgId).containsExactlyInAnyOrder(50L, 60L);
        // dry-run never queries by id when running all
        verify(ruleRepository, never()).findById(any());
    }

    @Test
    void dryRunAll_with_no_active_rules_returns_empty_list() {
        when(ruleRepository.findAllActive(any())).thenReturn(List.of());
        List<CostAllocationLine> lines = service.dryRunAll(START, END);
        assertThat(lines).isEmpty();
    }
}
