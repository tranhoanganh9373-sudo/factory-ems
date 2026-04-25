package com.ems.mockdata.seed;

import com.ems.tariff.entity.TariffPeriod;
import com.ems.tariff.entity.TariffPlan;
import com.ems.tariff.repository.TariffPeriodRepository;
import com.ems.tariff.repository.TariffPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Two tariff plans:
 *   Plan A (peak-valley, standard): SHARP 09-11 / PEAK 08-09,11-12,17-21 / FLAT rest / VALLEY 22-06 (cross-midnight)
 *   Plan B (flat-rate, off-season):  FLAT 00-24 only
 */
@Component
public class TariffSeeder {

    private static final Logger log = LoggerFactory.getLogger(TariffSeeder.class);
    private static final String PREFIX = OrgTreeSeeder.PREFIX;

    private final TariffPlanRepository planRepo;
    private final TariffPeriodRepository periodRepo;
    private final JdbcTemplate jdbc;

    public TariffSeeder(TariffPlanRepository planRepo, TariffPeriodRepository periodRepo,
                        JdbcTemplate jdbc) {
        this.planRepo = planRepo;
        this.periodRepo = periodRepo;
        this.jdbc = jdbc;
    }

    @Transactional
    public void seed() {
        if (planRepo.findAll().stream().anyMatch(p -> p.getName().startsWith(PREFIX))) {
            log.info("Tariffs already seeded, skipping");
            return;
        }
        log.info("Seeding tariff plans...");

        Long elecTypeId = jdbc.queryForObject(
            "SELECT id FROM energy_types WHERE code = 'ELEC'", Long.class);

        // Plan A: peak-valley with cross-midnight valley period
        TariffPlan planA = new TariffPlan();
        planA.setName(PREFIX + "峰谷电价方案");
        planA.setEnergyTypeId(elecTypeId);
        planA.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        planA.setEnabled(true);
        planRepo.save(planA);

        // SHARP: 09:00-11:00  1.2000
        addPeriod(planA.getId(), "SHARP",  "09:00", "11:00", "1.2000");
        // PEAK:  08:00-09:00  0.9800
        addPeriod(planA.getId(), "PEAK",   "08:00", "09:00", "0.9800");
        // PEAK:  11:00-12:00  0.9800
        addPeriod(planA.getId(), "PEAK",   "11:00", "12:00", "0.9800");
        // PEAK:  17:00-21:00  0.9800
        addPeriod(planA.getId(), "PEAK",   "17:00", "21:00", "0.9800");
        // FLAT:  12:00-17:00  0.6500
        addPeriod(planA.getId(), "FLAT",   "12:00", "17:00", "0.6500");
        // FLAT:  21:00-22:00  0.6500
        addPeriod(planA.getId(), "FLAT",   "21:00", "22:00", "0.6500");
        // FLAT:  06:00-08:00  0.6500
        addPeriod(planA.getId(), "FLAT",   "06:00", "08:00", "0.6500");
        // VALLEY: 22:00-06:00 cross-midnight  0.3200
        addPeriod(planA.getId(), "VALLEY", "22:00", "06:00", "0.3200");

        // Plan B: flat rate (simpler, for off-season billing)
        TariffPlan planB = new TariffPlan();
        planB.setName(PREFIX + "平价电价方案");
        planB.setEnergyTypeId(elecTypeId);
        planB.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        planB.setEnabled(true);
        planRepo.save(planB);

        // Only FLAT covering full 24h
        addPeriod(planB.getId(), "FLAT",   "00:00", "00:00", "0.6500");

        log.info("Seeded 2 tariff plans");
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
