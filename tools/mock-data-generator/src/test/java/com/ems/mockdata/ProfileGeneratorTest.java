package com.ems.mockdata;

import com.ems.mockdata.timeseries.ProfileGenerator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileGeneratorTest {

    private final ProfileGenerator gen = new ProfileGenerator();

    @Test
    void electricFactor_sharpHour_isMaximum() {
        // 09:00 weekday -> sharp, should be 1.0 * seasonal * 1.0
        LocalDateTime ldt = LocalDateTime.of(2026, 3, 10, 9, 0); // Tuesday
        double factor = gen.electricFactor(ldt);
        assertThat(factor).isGreaterThan(0.9);
    }

    @Test
    void electricFactor_valleyHour_isLow() {
        // 03:00 weekday -> valley
        LocalDateTime ldt = LocalDateTime.of(2026, 3, 10, 3, 0);
        double factor = gen.electricFactor(ldt);
        assertThat(factor).isLessThan(0.5);
    }

    @Test
    void electricFactor_weekend_isReduced() {
        // 09:00 Saturday -> weekend factor applied
        LocalDateTime weekday = LocalDateTime.of(2026, 3, 10, 9, 0); // Tuesday
        LocalDateTime weekend = LocalDateTime.of(2026, 3, 14, 9, 0); // Saturday
        double wd = gen.electricFactor(weekday);
        double we = gen.electricFactor(weekend);
        assertThat(we).isLessThan(wd * 0.6);
    }

    @Test
    void electricFactor_dayNightRatio_withinExpectedRange() {
        LocalDateTime peak = LocalDateTime.of(2026, 3, 10, 9, 0);
        LocalDateTime valley = LocalDateTime.of(2026, 3, 10, 3, 0);
        double ratio = gen.electricFactor(peak) / gen.electricFactor(valley);
        // should be between 2x and 5x
        assertThat(ratio).isBetween(2.0, 5.0);
    }

    @Test
    void waterFactor_businessHours_higherThanNight() {
        LocalDateTime day = LocalDateTime.of(2026, 3, 10, 10, 0);
        LocalDateTime night = LocalDateTime.of(2026, 3, 10, 2, 0);
        assertThat(gen.waterFactor(day)).isGreaterThan(gen.waterFactor(night));
    }

    @Test
    void steamFactor_neverNegative() {
        for (int h = 0; h < 24; h++) {
            LocalDateTime ldt = LocalDateTime.of(2026, 3, 10, h, 0);
            assertThat(gen.steamFactor(ldt)).isGreaterThanOrEqualTo(0.0);
        }
    }
}
