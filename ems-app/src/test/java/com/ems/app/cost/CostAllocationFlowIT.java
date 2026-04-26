package com.ems.app.cost;

import com.ems.app.FactoryEmsApplication;
import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.CostAllocationRun;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.entity.RunStatus;
import com.ems.cost.repository.CostAllocationLineRepository;
import com.ems.cost.repository.CostAllocationRuleRepository;
import com.ems.cost.repository.CostAllocationRunRepository;
import com.ems.cost.service.CostAllocationService;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import com.ems.orgtree.entity.OrgNode;
import com.ems.orgtree.repository.OrgNodeRepository;
import com.ems.tariff.entity.TariffPeriod;
import com.ems.tariff.entity.TariffPlan;
import com.ems.tariff.repository.TariffPeriodRepository;
import com.ems.tariff.repository.TariffPlanRepository;
import com.ems.timeseries.rollup.entity.RollupHourly;
import com.ems.timeseries.rollup.repository.RollupHourlyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Cost-allocation full flow integration test.
 *
 * Seeds: org tree (1 plant + 2 workshops), 1 source meter, 24h hourly usage,
 * 4-band tariff plan, 1 PROPORTIONAL FIXED rule (60/40).
 * Asserts: lines persisted, 4-band split sums match total, total = sum(usage*price),
 * and rerun supersedes the prior SUCCESS.
 */
@SpringBootTest(classes = FactoryEmsApplication.class)
@ActiveProfiles("test")
@Testcontainers
class CostAllocationFlowIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final OffsetDateTime PERIOD_START = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
    private static final OffsetDateTime PERIOD_END   = PERIOD_START.plusHours(24);

    @Autowired CostAllocationService service;
    @Autowired CostAllocationRuleRepository ruleRepo;
    @Autowired CostAllocationRunRepository runRepo;
    @Autowired CostAllocationLineRepository lineRepo;
    @Autowired OrgNodeRepository orgRepo;
    @Autowired MeterRepository meterRepo;
    @Autowired TariffPlanRepository planRepo;
    @Autowired TariffPeriodRepository periodRepo;
    @Autowired RollupHourlyRepository rollupRepo;
    @Autowired JdbcTemplate jdbc;

    private Long sourceMeterId;
    private Long workshopAId;
    private Long workshopBId;
    private Long ruleId;

    @BeforeEach
    void seed() {
        // Wipe just the cost tables; everything else can stay across tests in the suite
        jdbc.update("DELETE FROM cost_allocation_line");
        jdbc.update("DELETE FROM cost_allocation_run");
        jdbc.update("DELETE FROM cost_allocation_rule");

        // org tree (V1.0.8 already seeds reference data; we add our own under unique codes)
        OrgNode plant = newOrg(null, "IT-PLANT", "测试厂区");
        plant = orgRepo.save(plant);
        OrgNode wsA = newOrg(plant.getId(), "IT-WS-A", "车间A");
        OrgNode wsB = newOrg(plant.getId(), "IT-WS-B", "车间B");
        wsA = orgRepo.save(wsA);
        wsB = orgRepo.save(wsB);
        workshopAId = wsA.getId();
        workshopBId = wsB.getId();

        // energy_type ELEC is seeded by V1.0.8; pull its id
        Long elecId = jdbc.queryForObject(
                "SELECT id FROM energy_types WHERE code = 'ELEC'", Long.class);

        // source meter under plant
        Meter m = new Meter();
        m.setCode("IT-METER-1");
        m.setName("plant total");
        m.setEnergyTypeId(elecId);
        m.setOrgNodeId(plant.getId());
        m.setInfluxMeasurement("electricity");
        m.setInfluxTagKey("meter_id");
        m.setInfluxTagValue("IT-METER-1");
        m.setEnabled(true);
        m = meterRepo.save(m);
        sourceMeterId = m.getId();

        // 24 h × 10 kWh — flat usage to make math obvious
        for (int h = 0; h < 24; h++) {
            RollupHourly r = new RollupHourly();
            r.setMeterId(sourceMeterId);
            r.setHourTs(PERIOD_START.plusHours(h));
            r.setOrgNodeId(plant.getId());
            BigDecimal v = new BigDecimal("10");
            r.setSumValue(v); r.setAvgValue(v); r.setMaxValue(v); r.setMinValue(v);
            r.setCount(60);
            r.setUpdatedAt(OffsetDateTime.now());
            rollupRepo.save(r);
        }

        // 4-band tariff plan: SHARP 19-21 (1.20), PEAK 8-19 (0.80),
        // FLAT 21-23 + 6-8 (0.50), VALLEY 23-6 (0.30)
        TariffPlan plan = new TariffPlan();
        plan.setName("IT-TARIFF");
        plan.setEnergyTypeId(elecId);
        plan.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        plan.setEnabled(true);
        plan = planRepo.save(plan);

        addPeriod(plan.getId(), "SHARP", "19:00", "21:00", "1.20");
        addPeriod(plan.getId(), "PEAK",  "08:00", "19:00", "0.80");
        addPeriod(plan.getId(), "FLAT",  "21:00", "23:00", "0.50");
        addPeriod(plan.getId(), "FLAT",  "06:00", "08:00", "0.50");
        addPeriod(plan.getId(), "VALLEY","23:00", "06:00", "0.30");

        // PROPORTIONAL rule: split plant total 60/40 to A/B by FIXED weights
        CostAllocationRule rule = new CostAllocationRule();
        rule.setCode("IT-RULE-1");
        rule.setName("plant -> A/B 60/40");
        rule.setEnergyType(EnergyTypeCode.ELEC);
        rule.setAlgorithm(AllocationAlgorithm.PROPORTIONAL);
        rule.setSourceMeterId(sourceMeterId);
        rule.setTargetOrgIds(new Long[]{workshopAId, workshopBId});
        Map<String, Object> w = new HashMap<>();
        w.put("basis", "FIXED");
        Map<String, Object> values = new HashMap<>();
        values.put(workshopAId.toString(), 0.6);
        values.put(workshopBId.toString(), 0.4);
        w.put("values", values);
        rule.setWeights(w);
        rule.setPriority(100);
        rule.setEnabled(true);
        rule.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        rule = ruleRepo.save(rule);
        ruleId = rule.getId();
    }

    @Test
    void async_run_persists_lines_with_4band_split_and_supersedes_on_rerun() {
        // ---- first run ----
        Long firstRunId = service.submitRun(PERIOD_START, PERIOD_END, List.of(ruleId), 1L);

        await().atMost(30, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    CostAllocationRun r = runRepo.findById(firstRunId).orElseThrow();
                    assertThat(r.getStatus()).isEqualTo(RunStatus.SUCCESS);
                });

        CostAllocationRun first = runRepo.findById(firstRunId).orElseThrow();
        List<CostAllocationLine> lines = lineRepo.findByRunId(firstRunId);
        assertThat(lines).hasSize(2); // one line per target org

        // total = 24h * 10 kWh = 240 kWh; expected charge:
        //   SHARP 2h@1.20 = 24, PEAK 11h@0.80 = 88, FLAT 4h@0.50 = 20, VALLEY 7h@0.30 = 21
        //   sum per kWh-hour = 153; multiplied by 10kWh hourly load → 153.0000
        BigDecimal expectedTotal = new BigDecimal("153.0000");
        assertThat(first.getTotalAmount().setScale(4, RoundingMode.HALF_UP))
                .isEqualByComparingTo(expectedTotal);

        // workshop A receives 60%, B receives 40% — both per-band and total
        CostAllocationLine a = pickByOrg(lines, workshopAId);
        CostAllocationLine b = pickByOrg(lines, workshopBId);
        assertThat(a.getAmount().add(b.getAmount())).isEqualByComparingTo(expectedTotal);
        assertThat(a.getAmount()).isEqualByComparingTo(new BigDecimal("91.8000"));  // 153 * 0.6
        assertThat(b.getAmount()).isEqualByComparingTo(new BigDecimal("61.2000"));  // 153 * 0.4

        // 4-band split sums per line equal line.amount
        assertThat(sumBands(a)).isEqualByComparingTo(a.getAmount());
        assertThat(sumBands(b)).isEqualByComparingTo(b.getAmount());

        // ---- second run, same period: prior SUCCESS becomes SUPERSEDED ----
        Long secondRunId = service.submitRun(PERIOD_START, PERIOD_END, List.of(ruleId), 1L);
        await().atMost(30, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    CostAllocationRun r = runRepo.findById(secondRunId).orElseThrow();
                    assertThat(r.getStatus()).isEqualTo(RunStatus.SUCCESS);
                });

        CostAllocationRun supersededFirst = runRepo.findById(firstRunId).orElseThrow();
        assertThat(supersededFirst.getStatus()).isEqualTo(RunStatus.SUPERSEDED);
        assertThat(runRepo.findSuccessByPeriod(PERIOD_START, PERIOD_END))
                .map(CostAllocationRun::getId).contains(secondRunId);
    }

    private static CostAllocationLine pickByOrg(List<CostAllocationLine> lines, Long orgId) {
        return lines.stream().filter(l -> orgId.equals(l.getTargetOrgId())).findFirst().orElseThrow();
    }

    private static BigDecimal sumBands(CostAllocationLine l) {
        return nz(l.getSharpAmount()).add(nz(l.getPeakAmount()))
                .add(nz(l.getFlatAmount())).add(nz(l.getValleyAmount()));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private OrgNode newOrg(Long parentId, String code, String name) {
        OrgNode o = new OrgNode();
        o.setParentId(parentId);
        o.setCode(code);
        o.setName(name);
        o.setNodeType(parentId == null ? "PLANT" : "WORKSHOP");
        return o;
    }

    private void addPeriod(Long planId, String type, String start, String end, String price) {
        TariffPeriod p = new TariffPeriod();
        p.setPlanId(planId);
        p.setPeriodType(type);
        p.setTimeStart(LocalTime.parse(start));
        p.setTimeEnd(LocalTime.parse(end));
        p.setPricePerUnit(new BigDecimal(price));
        periodRepo.save(p);
    }
}
