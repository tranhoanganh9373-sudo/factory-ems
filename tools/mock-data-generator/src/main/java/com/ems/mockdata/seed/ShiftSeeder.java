package com.ems.mockdata.seed;

import com.ems.production.entity.Shift;
import com.ems.production.repository.ShiftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

/**
 * Seeds 3 shifts: day 06-14 / mid 14-22 / night 22-06 (cross-midnight).
 * Cross-midnight: time_start > time_end per schema comment.
 */
@Component
public class ShiftSeeder {

    private static final Logger log = LoggerFactory.getLogger(ShiftSeeder.class);
    public static final String SHIFT_DAY   = OrgTreeSeeder.PREFIX + "SHIFT-DAY";
    public static final String SHIFT_MID   = OrgTreeSeeder.PREFIX + "SHIFT-MID";
    public static final String SHIFT_NIGHT = OrgTreeSeeder.PREFIX + "SHIFT-NIGHT";

    private final ShiftRepository shiftRepo;

    public ShiftSeeder(ShiftRepository shiftRepo) {
        this.shiftRepo = shiftRepo;
    }

    @Transactional
    public void seed() {
        if (shiftRepo.existsByCode(SHIFT_DAY)) {
            log.info("Shifts already seeded, skipping");
            return;
        }
        log.info("Seeding shifts...");
        createShift(SHIFT_DAY,   "早班", LocalTime.of(6, 0),  LocalTime.of(14, 0), 1);
        createShift(SHIFT_MID,   "中班", LocalTime.of(14, 0), LocalTime.of(22, 0), 2);
        // cross-midnight: start=22:00 > end=06:00
        createShift(SHIFT_NIGHT, "夜班", LocalTime.of(22, 0), LocalTime.of(6, 0),  3);
        log.info("Seeded 3 shifts");
    }

    private void createShift(String code, String name, LocalTime start, LocalTime end, int sort) {
        Shift s = new Shift();
        s.setCode(code);
        s.setName(name);
        s.setTimeStart(start);
        s.setTimeEnd(end);
        s.setSortOrder(sort);
        s.setEnabled(true);
        shiftRepo.save(s);
    }
}
