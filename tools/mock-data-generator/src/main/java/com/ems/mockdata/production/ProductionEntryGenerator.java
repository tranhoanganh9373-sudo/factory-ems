package com.ems.mockdata.production;

import com.ems.orgtree.entity.OrgNode;
import com.ems.orgtree.repository.OrgNodeRepository;
import com.ems.production.entity.ProductionEntry;
import com.ems.production.entity.Shift;
import com.ems.production.repository.ProductionEntryRepository;
import com.ems.production.repository.ShiftRepository;
import com.ems.mockdata.seed.OrgTreeSeeder;
import com.ems.mockdata.seed.ShiftSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

/**
 * Generates production entries: 4 workshops x days x 3 shifts.
 * Quantity is correlated with shift index (day > mid > night) + ±15% noise.
 * Weekends: 50% of workshops stop (skip entry).
 */
@Component
public class ProductionEntryGenerator {

    private static final Logger log = LoggerFactory.getLogger(ProductionEntryGenerator.class);
    private static final String PREFIX = OrgTreeSeeder.PREFIX;

    // 6 product SKU codes
    private static final String[] PRODUCTS = {
        "SKU-A001", "SKU-A002", "SKU-B001", "SKU-B002", "SKU-C001", "SKU-C002"
    };
    // base quantities per shift (day > mid > night)
    private static final double[] SHIFT_BASE = { 120.0, 100.0, 80.0 };

    private final OrgNodeRepository orgNodeRepo;
    private final ShiftRepository shiftRepo;
    private final ProductionEntryRepository entryRepo;
    private final JdbcTemplate jdbc;

    public ProductionEntryGenerator(OrgNodeRepository orgNodeRepo,
                                    ShiftRepository shiftRepo,
                                    ProductionEntryRepository entryRepo,
                                    JdbcTemplate jdbc) {
        this.orgNodeRepo = orgNodeRepo;
        this.shiftRepo = shiftRepo;
        this.entryRepo = entryRepo;
        this.jdbc = jdbc;
    }

    @Transactional
    public void generate(long seed, LocalDate startDate, LocalDate endDate) {
        long existing = entryRepo.count();
        if (existing > 0) {
            log.info("ProductionEntries already exist ({}), skipping", existing);
            return;
        }
        log.info("Generating production entries {} to {}", startDate, endDate);

        Random rng = new Random(seed + 100);

        List<OrgNode> workshops = orgNodeRepo.findAll().stream()
            .filter(n -> "WORKSHOP".equals(n.getNodeType()) && n.getCode().startsWith(PREFIX))
            .toList();

        List<Shift> shifts = shiftRepo.findAll().stream()
            .filter(s -> s.getCode().startsWith(PREFIX))
            .toList();

        if (workshops.isEmpty() || shifts.isEmpty()) {
            log.warn("No workshops or shifts found. Run seeders first.");
            return;
        }

        int total = 0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate.minusDays(1))) {
            boolean isWeekend = cursor.getDayOfWeek() == DayOfWeek.SATURDAY
                || cursor.getDayOfWeek() == DayOfWeek.SUNDAY;

            for (int wi = 0; wi < workshops.size(); wi++) {
                OrgNode ws = workshops.get(wi);

                // 50% of workshops stop on weekend
                if (isWeekend && (wi % 2 == 0)) continue;

                for (int si = 0; si < shifts.size(); si++) {
                    Shift shift = shifts.get(si);
                    String productCode = PRODUCTS[(wi * 3 + si) % PRODUCTS.length];

                    double base = SHIFT_BASE[si % SHIFT_BASE.length];
                    double noise = 1.0 + (rng.nextDouble() * 0.30 - 0.15); // ±15%
                    double qty = base * noise;
                    if (isWeekend) qty *= 0.6;

                    ProductionEntry e = new ProductionEntry();
                    e.setOrgNodeId(ws.getId());
                    e.setShiftId(shift.getId());
                    e.setEntryDate(cursor);
                    e.setProductCode(productCode);
                    e.setQuantity(BigDecimal.valueOf(qty).setScale(4, RoundingMode.HALF_UP));
                    e.setUnit("件");
                    entryRepo.save(e);
                    total++;
                }
            }
            cursor = cursor.plusDays(1);
        }
        log.info("Generated {} production entries", total);
    }
}
