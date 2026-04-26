package com.ems.app.billing;

import com.ems.app.FactoryEmsApplication;
import com.ems.billing.dto.BillDTO;
import com.ems.billing.dto.BillPeriodDTO;
import com.ems.billing.entity.BillPeriodStatus;
import com.ems.billing.repository.BillLineRepository;
import com.ems.billing.service.BillingService;
import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.CostAllocationRun;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.entity.RunStatus;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Plan 2.2 Phase M — bill lifecycle integration test.
 *
 * 完整跑通：
 *   1) seed cost run → SUCCESS
 *   2) ensurePeriod(2026-03) → OPEN
 *   3) generateBills → bills 落库 + period CLOSED + bill_line 写源链
 *   4) lock period → LOCKED
 *   5) generateBills again → 拒绝（IllegalStateException, LOCKED）
 *   6) unlock period → CLOSED
 *   7) generateBills 再来一次 → 旧 bill 被删除 + 新 bill 写入（重写策略）
 */
@SpringBootTest(classes = FactoryEmsApplication.class)
@ActiveProfiles("test")
@Testcontainers
class BillLifecycleIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    // 使用 2026-03 的整月作为 cost run period，因 BillPeriod 的 covering 检查要求 cost.period 完全覆盖 bill.period
    private static final OffsetDateTime PERIOD_START = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
    private static final OffsetDateTime PERIOD_END   = OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, Z);

    @Autowired CostAllocationService costService;
    @Autowired CostAllocationRuleRepository ruleRepo;
    @Autowired CostAllocationRunRepository runRepo;
    @Autowired BillingService billing;
    @Autowired BillLineRepository billLineRepo;
    @Autowired OrgNodeRepository orgRepo;
    @Autowired MeterRepository meterRepo;
    @Autowired TariffPlanRepository planRepo;
    @Autowired TariffPeriodRepository periodRepo;
    @Autowired RollupHourlyRepository rollupRepo;
    @Autowired JdbcTemplate jdbc;

    private Long workshopAId;
    private Long workshopBId;
    private Long sourceMeterId;
    private Long ruleId;

    @BeforeEach
    void seed() {
        // 清掉本测可能残留的 cost / billing 数据；其他模块的 seed 保留
        jdbc.update("DELETE FROM bill_line");
        jdbc.update("DELETE FROM bill");
        jdbc.update("DELETE FROM bill_period");
        jdbc.update("DELETE FROM cost_allocation_line");
        jdbc.update("DELETE FROM cost_allocation_run");
        jdbc.update("DELETE FROM cost_allocation_rule");

        OrgNode plant = orgRepo.save(newOrg(null, "BL-IT-PLANT", "测试厂区(BL)"));
        OrgNode wsA = orgRepo.save(newOrg(plant.getId(), "BL-IT-WS-A", "车间A(BL)"));
        OrgNode wsB = orgRepo.save(newOrg(plant.getId(), "BL-IT-WS-B", "车间B(BL)"));
        workshopAId = wsA.getId();
        workshopBId = wsB.getId();

        Long elecId = jdbc.queryForObject("SELECT id FROM energy_types WHERE code = 'ELEC'", Long.class);

        Meter m = new Meter();
        m.setCode("BL-IT-METER-1");
        m.setName("plant total (BL)");
        m.setEnergyTypeId(elecId);
        m.setOrgNodeId(plant.getId());
        m.setInfluxMeasurement("electricity");
        m.setInfluxTagKey("meter_id");
        m.setInfluxTagValue("BL-IT-METER-1");
        m.setEnabled(true);
        m = meterRepo.save(m);
        sourceMeterId = m.getId();

        // 整月每小时 1 kWh 简化算术；30×24 = 720 kWh
        OffsetDateTime t = PERIOD_START;
        while (t.isBefore(PERIOD_END)) {
            RollupHourly r = new RollupHourly();
            r.setMeterId(sourceMeterId);
            r.setHourTs(t);
            r.setOrgNodeId(plant.getId());
            BigDecimal v = BigDecimal.ONE;
            r.setSumValue(v); r.setAvgValue(v); r.setMaxValue(v); r.setMinValue(v);
            r.setCount(60);
            r.setUpdatedAt(OffsetDateTime.now());
            rollupRepo.save(r);
            t = t.plusHours(1);
        }

        TariffPlan plan = new TariffPlan();
        plan.setName("BL-IT-TARIFF");
        plan.setEnergyTypeId(elecId);
        plan.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        plan.setEnabled(true);
        plan = planRepo.save(plan);
        addPeriod(plan.getId(), "FLAT", "00:00", "23:59", "1.00");   // 平价 1.0 全天

        CostAllocationRule rule = new CostAllocationRule();
        rule.setCode("BL-IT-RULE-1");
        rule.setName("BL plant -> A/B 60/40");
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
        ruleId = ruleRepo.save(rule).getId();
    }

    @Test
    void close_lock_unlock_reclose_full_lifecycle() {
        // 1) cost run → SUCCESS
        Long runId = costService.submitRun(PERIOD_START, PERIOD_END, List.of(ruleId), 1L);
        await().atMost(60, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    CostAllocationRun r = runRepo.findById(runId).orElseThrow();
                    assertThat(r.getStatus()).isEqualTo(RunStatus.SUCCESS);
                });

        // 2) ensurePeriod
        BillPeriodDTO p = billing.ensurePeriod(YearMonth.of(2026, 3));
        assertThat(p.status()).isEqualTo(BillPeriodStatus.OPEN);

        // 3) generateBills → CLOSED + bills 落库
        BillPeriodDTO closed = billing.generateBills(p.id(), 1L);
        assertThat(closed.status()).isEqualTo(BillPeriodStatus.CLOSED);
        List<BillDTO> bills = billing.listBills(p.id(), null);
        assertThat(bills).hasSize(2);   // 两个车间 × 1 能源（ELEC）
        BillDTO billA = bills.stream().filter(b -> b.orgNodeId().equals(workshopAId)).findFirst().orElseThrow();
        BillDTO billB = bills.stream().filter(b -> b.orgNodeId().equals(workshopBId)).findFirst().orElseThrow();
        // total 720 kWh × 1.0 = 720 元；A=432，B=288
        assertThat(billA.amount()).isEqualByComparingTo("432");
        assertThat(billB.amount()).isEqualByComparingTo("288");
        assertThat(billA.runId()).isEqualTo(runId);

        // bill_line 应至少存在
        assertThat(billLineRepo.findByBillId(billA.id())).isNotEmpty();

        // 4) lock → LOCKED
        BillPeriodDTO locked = billing.lockPeriod(p.id(), 1L);
        assertThat(locked.status()).isEqualTo(BillPeriodStatus.LOCKED);
        assertThat(locked.lockedBy()).isEqualTo(1L);

        // 5) generateBills 再来一次 → 应被拒绝
        assertThatThrownBy(() -> billing.generateBills(p.id(), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCKED");

        // 6) unlock → CLOSED
        BillPeriodDTO unlocked = billing.unlockPeriod(p.id(), 1L);
        assertThat(unlocked.status()).isEqualTo(BillPeriodStatus.CLOSED);
        assertThat(unlocked.lockedBy()).isNull();

        // 7) 重写：再 generateBills 一次。先记一下旧 billA.id 用作"被删"证据
        Long oldBillAId = billA.id();
        BillPeriodDTO recl = billing.generateBills(p.id(), 1L);
        assertThat(recl.status()).isEqualTo(BillPeriodStatus.CLOSED);
        List<BillDTO> bills2 = billing.listBills(p.id(), null);
        assertThat(bills2).hasSize(2);
        // 旧 bill id 应不再存在（重写时整批 DELETE → 重新 INSERT，新 id 序列）
        assertThat(bills2).extracting(BillDTO::id).doesNotContain(oldBillAId);
    }

    private OrgNode newOrg(Long parentId, String code, String name) {
        OrgNode o = new OrgNode();
        o.setParentId(parentId);
        o.setCode(code);
        o.setName(name);
        o.setNodeType(parentId == null ? "PLANT" : "WORKSHOP");
        return o;
    }

    private void addPeriod(Long planId, String type, String start, String end, String price) {
        TariffPeriod tp = new TariffPeriod();
        tp.setPlanId(planId);
        tp.setPeriodType(type);
        tp.setTimeStart(LocalTime.parse(start));
        tp.setTimeEnd(LocalTime.parse(end));
        tp.setPricePerUnit(new BigDecimal(price));
        periodRepo.save(tp);
    }
}
