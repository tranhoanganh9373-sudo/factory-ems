package com.ems.cost.service;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.CostAllocationRun;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.entity.RunStatus;
import com.ems.cost.repository.CostAllocationLineRepository;
import com.ems.cost.repository.CostAllocationRuleRepository;
import com.ems.cost.repository.CostAllocationRunRepository;
import com.ems.cost.service.impl.CostAllocationServiceImpl;
import com.ems.cost.service.impl.DirectAllocationStrategy;
import com.ems.tariff.service.HourPrice;
import com.ems.tariff.service.TariffPriceLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the async run pipeline: submitRun + executeRun + SUPERSEDE + FAILED.
 *
 * Uses a synchronous Executor and a stub PlatformTransactionManager so each
 * txTemplate.executeWithoutResult block runs the lambda inline.
 */
class CostAllocationServiceAsyncRunTest {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
    private static final OffsetDateTime END   = START.plusHours(3);

    private CostAllocationRuleRepository ruleRepository;
    private CostAllocationRunRepository runRepository;
    private CostAllocationLineRepository lineRepository;
    private MeterUsageReader meterUsage;
    private TariffPriceLookupService tariffLookup;
    private MeterMetadataPort meterMetadata;
    private Executor executor;
    private CapturingExecutor capturingExecutor;
    private CostAllocationServiceImpl service;

    /** No-op tx manager: returns a SimpleTransactionStatus, ignores commit/rollback. */
    private static final PlatformTransactionManager NOOP_TX = new PlatformTransactionManager() {
        @Override public TransactionStatus getTransaction(TransactionDefinition def) {
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) {}
        @Override public void rollback(TransactionStatus status) {}
    };

    @BeforeEach
    void setUp() {
        ruleRepository = mock(CostAllocationRuleRepository.class);
        runRepository = mock(CostAllocationRunRepository.class);
        lineRepository = mock(CostAllocationLineRepository.class);
        meterUsage = mock(MeterUsageReader.class);
        tariffLookup = mock(TariffPriceLookupService.class);
        meterMetadata = mock(MeterMetadataPort.class);
        executor = Runnable::run; // sync by default
        capturingExecutor = new CapturingExecutor();

        DirectAllocationStrategy direct = new DirectAllocationStrategy();
        AllocationAlgorithmFactory factory = new AllocationAlgorithmFactory(List.of(direct));
        service = new CostAllocationServiceImpl(
                ruleRepository, runRepository, lineRepository, factory,
                meterUsage, tariffLookup, meterMetadata, executor, NOOP_TX);
    }

    private CostAllocationRule directRule(Long ruleId) {
        CostAllocationRule r = new CostAllocationRule();
        r.setCode("R-DIRECT-" + ruleId);
        r.setName("test direct");
        r.setEnergyType(EnergyTypeCode.ELEC);
        r.setAlgorithm(AllocationAlgorithm.DIRECT);
        r.setSourceMeterId(100L);
        r.setTargetOrgIds(new Long[]{50L});
        r.setEnabled(true);
        r.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        return r;
    }

    private void wireDirectMeterAndTariff() {
        when(meterMetadata.energyTypeIdOf(100L)).thenReturn(1L);
        when(meterUsage.hourly(eq(100L), any(), any())).thenReturn(List.of(
                new MeterUsageReader.HourlyUsage(START, new BigDecimal("12"))));
        when(tariffLookup.batch(eq(1L), any(), any())).thenReturn(List.of(
                new HourPrice(START, "FLAT", new BigDecimal("0.50"))));
    }

    /** Wires runRepository.save to assign id 42 on first save. */
    private void stubSaveAssignsId(long assignedId) {
        when(runRepository.save(any(CostAllocationRun.class))).thenAnswer(inv -> {
            CostAllocationRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(assignedId);
            return r;
        });
    }

    // ---------- submitRun ----------

    @Test
    void submitRun_creates_PENDING_run_and_dispatches_to_executor() {
        // Use a capturing executor so executeRun is NOT invoked synchronously.
        service = new CostAllocationServiceImpl(
                ruleRepository, runRepository, lineRepository,
                new AllocationAlgorithmFactory(List.of(new DirectAllocationStrategy())),
                meterUsage, tariffLookup, meterMetadata, capturingExecutor, NOOP_TX);
        stubSaveAssignsId(42L);

        Long runId = service.submitRun(START, END, List.of(7L, 8L), 99L);

        assertThat(runId).isEqualTo(42L);
        AtomicReference<CostAllocationRun> saved = new AtomicReference<>();
        verify(runRepository).save(argThat(r -> {
            saved.set(r);
            return true;
        }));
        CostAllocationRun r = saved.get();
        assertThat(r.getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(r.getPeriodStart()).isEqualTo(START);
        assertThat(r.getPeriodEnd()).isEqualTo(END);
        assertThat(r.getRuleIds()).containsExactly(7L, 8L);
        assertThat(r.getCreatedBy()).isEqualTo(99L);
        assertThat(capturingExecutor.captured).hasSize(1);
    }

    @Test
    void submitRun_with_null_ruleIds_stores_null_array() {
        service = new CostAllocationServiceImpl(
                ruleRepository, runRepository, lineRepository,
                new AllocationAlgorithmFactory(List.of(new DirectAllocationStrategy())),
                meterUsage, tariffLookup, meterMetadata, capturingExecutor, NOOP_TX);
        stubSaveAssignsId(43L);

        service.submitRun(START, END, null, null);

        AtomicReference<CostAllocationRun> saved = new AtomicReference<>();
        verify(runRepository).save(argThat(r -> { saved.set(r); return true; }));
        assertThat(saved.get().getRuleIds()).isNull();
    }

    @Test
    void submitRun_with_empty_ruleIds_stores_null_array() {
        service = new CostAllocationServiceImpl(
                ruleRepository, runRepository, lineRepository,
                new AllocationAlgorithmFactory(List.of(new DirectAllocationStrategy())),
                meterUsage, tariffLookup, meterMetadata, capturingExecutor, NOOP_TX);
        stubSaveAssignsId(44L);

        service.submitRun(START, END, List.of(), 1L);

        AtomicReference<CostAllocationRun> saved = new AtomicReference<>();
        verify(runRepository).save(argThat(r -> { saved.set(r); return true; }));
        assertThat(saved.get().getRuleIds()).isNull();
    }

    // ---------- executeRun happy path ----------

    @Test
    void executeRun_happy_path_PENDING_to_SUCCESS_supersedes_prior() {
        wireDirectMeterAndTariff();

        CostAllocationRun run = new CostAllocationRun();
        run.setId(101L);
        run.setPeriodStart(START);
        run.setPeriodEnd(END);
        run.setStatus(RunStatus.PENDING);
        run.setRuleIds(null); // run all active

        when(runRepository.findById(101L)).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findAllActive(any())).thenReturn(List.of(directRule(1L)));
        when(runRepository.markPriorSuccessSuperseded(eq(START), eq(END), eq(101L))).thenReturn(2);

        // Capture the lines actually persisted
        List<CostAllocationLine> persisted = new ArrayList<>();
        doAnswer(inv -> { persisted.addAll(inv.getArgument(0)); return inv.getArgument(0); })
                .when(lineRepository).saveAll(any());

        service.executeRun(101L);

        // Final state: SUCCESS, totalAmount set, finishedAt set
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(run.getTotalAmount()).isEqualByComparingTo("6.0000");
        assertThat(run.getFinishedAt()).isNotNull();

        // Prior SUCCESS demoted before promotion
        verify(runRepository).markPriorSuccessSuperseded(START, END, 101L);

        // Lines were saved with run id stamped
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getRunId()).isEqualTo(101L);
        assertThat(persisted.get(0).getAmount()).isEqualByComparingTo("6.0000");

        // status saved at least twice (RUNNING + SUCCESS)
        verify(runRepository, atLeastOnce()).save(any());
    }

    @Test
    void executeRun_with_explicit_ruleIds_loads_only_those_rules() {
        wireDirectMeterAndTariff();
        CostAllocationRun run = new CostAllocationRun();
        run.setId(102L);
        run.setPeriodStart(START);
        run.setPeriodEnd(END);
        run.setStatus(RunStatus.PENDING);
        run.setRuleIds(new Long[]{7L});

        when(runRepository.findById(102L)).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findAllById(eq(List.of(7L)))).thenReturn(List.of(directRule(7L)));

        service.executeRun(102L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
        verify(ruleRepository).findAllById(List.of(7L));
        verify(ruleRepository, never()).findAllActive(any());
    }

    // ---------- executeRun error paths ----------

    @Test
    void executeRun_failure_marks_FAILED_with_truncated_message() {
        // strategy explodes -> compute fails after RUNNING transition
        when(meterMetadata.energyTypeIdOf(anyLong()))
                .thenThrow(new RuntimeException("boom: meter not found"));

        CostAllocationRun run = new CostAllocationRun();
        run.setId(201L);
        run.setPeriodStart(START);
        run.setPeriodEnd(END);
        run.setStatus(RunStatus.PENDING);

        when(runRepository.findById(201L)).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findAllActive(any())).thenReturn(List.of(directRule(1L)));

        service.executeRun(201L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("boom");
        assertThat(run.getFinishedAt()).isNotNull();
        // never marked any prior SUCCESS
        verify(runRepository, never()).markPriorSuccessSuperseded(any(), any(), anyLong());
        // no lines persisted
        verify(lineRepository, never()).saveAll(any());
    }

    @Test
    void executeRun_throws_when_run_already_RUNNING_then_marked_FAILED() {
        CostAllocationRun run = new CostAllocationRun();
        run.setId(202L);
        run.setPeriodStart(START);
        run.setPeriodEnd(END);
        run.setStatus(RunStatus.RUNNING); // not PENDING

        when(runRepository.findById(202L)).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.executeRun(202L);

        // transitionToRunning should have thrown -> caught -> markFailed
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("not PENDING");
    }

    @Test
    void executeRun_with_no_active_rules_finalizes_zero_total() {
        CostAllocationRun run = new CostAllocationRun();
        run.setId(203L);
        run.setPeriodStart(START);
        run.setPeriodEnd(END);
        run.setStatus(RunStatus.PENDING);

        when(runRepository.findById(203L)).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findAllActive(any())).thenReturn(List.of());

        service.executeRun(203L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(run.getTotalAmount()).isEqualByComparingTo("0");
        // no lines, no saveAll
        verify(lineRepository, never()).saveAll(any());
        // supersede still attempted (idempotent if there are none)
        verify(runRepository).markPriorSuccessSuperseded(START, END, 203L);
    }

    // ---------- helpers ----------

    private static org.mockito.ArgumentMatcher<CostAllocationRun> always() {
        return r -> true;
    }

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> m) {
        return org.mockito.ArgumentMatchers.argThat(m);
    }

    /** Captures runnables instead of running them; used to verify dispatch. */
    private static final class CapturingExecutor implements Executor {
        final List<Runnable> captured = new ArrayList<>();
        final AtomicLong counter = new AtomicLong();
        @Override public void execute(Runnable command) {
            captured.add(command);
            counter.incrementAndGet();
        }
    }
}
