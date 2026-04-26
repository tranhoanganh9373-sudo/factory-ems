package com.ems.cost.service.impl;

import com.ems.cost.async.CostAllocationExecutorConfig;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.CostAllocationRun;
import com.ems.cost.entity.RunStatus;
import com.ems.cost.repository.CostAllocationLineRepository;
import com.ems.cost.repository.CostAllocationRuleRepository;
import com.ems.cost.repository.CostAllocationRunRepository;
import com.ems.cost.service.AllocationAlgorithmFactory;
import com.ems.cost.service.AllocationContext;
import com.ems.cost.service.AllocationStrategy;
import com.ems.cost.service.CostAllocationService;
import com.ems.cost.service.MeterMetadataPort;
import com.ems.cost.service.MeterUsageReader;
import com.ems.tariff.service.TariffPriceLookupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@Service
public class CostAllocationServiceImpl implements CostAllocationService {

    private static final Logger log = LoggerFactory.getLogger(CostAllocationServiceImpl.class);

    private final CostAllocationRuleRepository ruleRepository;
    private final CostAllocationRunRepository runRepository;
    private final CostAllocationLineRepository lineRepository;
    private final AllocationAlgorithmFactory factory;
    private final MeterUsageReader meterUsage;
    private final TariffPriceLookupService tariffLookup;
    private final MeterMetadataPort meterMetadata;
    private final Executor executor;
    private final TransactionTemplate txTemplate;

    public CostAllocationServiceImpl(CostAllocationRuleRepository ruleRepository,
                                     CostAllocationRunRepository runRepository,
                                     CostAllocationLineRepository lineRepository,
                                     AllocationAlgorithmFactory factory,
                                     MeterUsageReader meterUsage,
                                     TariffPriceLookupService tariffLookup,
                                     MeterMetadataPort meterMetadata,
                                     @Qualifier(CostAllocationExecutorConfig.BEAN_NAME) Executor executor,
                                     PlatformTransactionManager txManager) {
        this.ruleRepository = ruleRepository;
        this.runRepository = runRepository;
        this.lineRepository = lineRepository;
        this.factory = factory;
        this.meterUsage = meterUsage;
        this.tariffLookup = tariffLookup;
        this.meterMetadata = meterMetadata;
        this.executor = executor;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // -------------------- dry-run --------------------

    @Override
    @Transactional(readOnly = true)
    public List<CostAllocationLine> dryRun(Long ruleId, OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        validatePeriod(periodStart, periodEnd);
        CostAllocationRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: id=" + ruleId));
        if (Boolean.FALSE.equals(rule.getEnabled())) {
            throw new IllegalArgumentException("Rule is disabled: id=" + ruleId);
        }
        if (!isEffectiveAt(rule, periodStart.toLocalDate())) {
            throw new IllegalArgumentException(
                    "Rule not effective at period start: id=" + ruleId
                    + " effective=[" + rule.getEffectiveFrom() + ".." + rule.getEffectiveTo() + "]");
        }
        return runOne(rule, periodStart, periodEnd);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostAllocationLine> dryRunAll(OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        validatePeriod(periodStart, periodEnd);
        List<CostAllocationRule> rules = ruleRepository.findAllActive(periodStart.toLocalDate());
        List<CostAllocationLine> out = new ArrayList<>();
        for (CostAllocationRule r : rules) {
            out.addAll(runOne(r, periodStart, periodEnd));
        }
        return out;
    }

    @Override
    public List<CostAllocationLine> runOne(CostAllocationRule rule,
                                           OffsetDateTime periodStart,
                                           OffsetDateTime periodEnd) {
        AllocationContext ctx = new AllocationContext(periodStart, periodEnd, meterUsage, tariffLookup, meterMetadata);
        AllocationStrategy strategy = factory.of(rule.getAlgorithm());
        return strategy.allocate(rule, ctx);
    }

    // -------------------- async run --------------------

    @Override
    @Transactional
    public Long submitRun(OffsetDateTime periodStart,
                          OffsetDateTime periodEnd,
                          List<Long> ruleIds,
                          Long createdBy) {
        validatePeriod(periodStart, periodEnd);
        CostAllocationRun run = new CostAllocationRun();
        run.setPeriodStart(periodStart);
        run.setPeriodEnd(periodEnd);
        run.setStatus(RunStatus.PENDING);
        run.setAlgorithmVersion("v1");
        run.setRuleIds(ruleIds == null || ruleIds.isEmpty() ? null : ruleIds.toArray(new Long[0]));
        run.setCreatedBy(createdBy);
        run = runRepository.save(run);
        Long id = run.getId();
        executor.execute(() -> {
            try {
                executeRun(id);
            } catch (Exception ex) {
                log.error("cost-alloc run id={} failed in worker", id, ex);
            }
        });
        return id;
    }

    @Override
    public void executeRun(Long runId) {
        try {
            transitionToRunning(runId);
            ExecutionResult result = computeAndPersist(runId);
            finalizeSuccess(runId, result.totalAmount);
            log.info("cost-alloc run id={} SUCCESS lines={} total={}", runId, result.lineCount, result.totalAmount);
        } catch (Exception ex) {
            log.error("cost-alloc run id={} FAILED", runId, ex);
            try {
                markFailed(runId, ex.getMessage());
            } catch (Exception innerEx) {
                log.error("cost-alloc run id={} unable to mark FAILED", runId, innerEx);
            }
        }
    }

    private void transitionToRunning(Long runId) {
        txTemplate.executeWithoutResult(status -> {
            CostAllocationRun run = runRepository.findById(runId)
                    .orElseThrow(() -> new IllegalStateException("Run vanished: id=" + runId));
            if (run.getStatus() != RunStatus.PENDING) {
                throw new IllegalStateException("Run not PENDING: id=" + runId + " status=" + run.getStatus());
            }
            run.setStatus(RunStatus.RUNNING);
            runRepository.save(run);
        });
    }

    /**
     * 计算 + 持久化 lines。算法执行不开事务（可能跑几十秒，避免占连接）；
     * 持久化在 saveLines 里独立短事务里完成。
     */
    private ExecutionResult computeAndPersist(Long runId) {
        CostAllocationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("Run vanished: id=" + runId));

        List<CostAllocationRule> rules;
        if (run.getRuleIds() == null || run.getRuleIds().length == 0) {
            rules = ruleRepository.findAllActive(run.getPeriodStart().toLocalDate());
        } else {
            rules = ruleRepository.findAllById(List.of(run.getRuleIds()));
        }

        List<CostAllocationLine> allLines = new ArrayList<>();
        for (CostAllocationRule rule : rules) {
            if (Boolean.FALSE.equals(rule.getEnabled())) continue;
            if (!isEffectiveAt(rule, run.getPeriodStart().toLocalDate())) continue;
            List<CostAllocationLine> lines = runOne(rule, run.getPeriodStart(), run.getPeriodEnd());
            for (CostAllocationLine ln : lines) ln.setRunId(runId);
            allLines.addAll(lines);
        }

        BigDecimal total = BigDecimal.ZERO;
        for (CostAllocationLine ln : allLines) total = total.add(ln.getAmount());

        if (!allLines.isEmpty()) saveLines(allLines);
        return new ExecutionResult(allLines.size(), total);
    }

    private void saveLines(List<CostAllocationLine> lines) {
        txTemplate.executeWithoutResult(status -> lineRepository.saveAll(lines));
    }

    private void finalizeSuccess(Long runId, BigDecimal totalAmount) {
        txTemplate.executeWithoutResult(status -> {
            CostAllocationRun run = runRepository.findById(runId)
                    .orElseThrow(() -> new IllegalStateException("Run vanished: id=" + runId));
            // demote any prior SUCCESS for this period BEFORE writing new SUCCESS,
            // so the partial-unique index (period × SUCCESS) doesn't fire.
            runRepository.markPriorSuccessSuperseded(run.getPeriodStart(), run.getPeriodEnd(), runId);
            run.setStatus(RunStatus.SUCCESS);
            run.setTotalAmount(totalAmount);
            run.setFinishedAt(OffsetDateTime.now());
            runRepository.save(run);
        });
    }

    private void markFailed(Long runId, String message) {
        txTemplate.executeWithoutResult(status -> {
            CostAllocationRun run = runRepository.findById(runId).orElse(null);
            if (run == null) return;
            run.setStatus(RunStatus.FAILED);
            run.setFinishedAt(OffsetDateTime.now());
            run.setErrorMessage(truncate(message));
            runRepository.save(run);
        });
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    @Override
    @Transactional(readOnly = true)
    public CostAllocationRun getRun(Long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: id=" + runId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostAllocationLine> getLines(Long runId, Long targetOrgId) {
        return targetOrgId == null
                ? lineRepository.findByRunId(runId)
                : lineRepository.findByRunIdAndTargetOrgId(runId, targetOrgId);
    }

    private record ExecutionResult(int lineCount, BigDecimal totalAmount) {}

    // -------------------- helpers --------------------

    private static void validatePeriod(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("periodStart / periodEnd must be non-null");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("periodEnd must be after periodStart: " + start + ".." + end);
        }
    }

    private static boolean isEffectiveAt(CostAllocationRule r, LocalDate at) {
        if (r.getEffectiveFrom() != null && at.isBefore(r.getEffectiveFrom())) return false;
        if (r.getEffectiveTo()   != null && at.isAfter(r.getEffectiveTo()))    return false;
        return true;
    }
}
