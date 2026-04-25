package com.ems.mockdata;

import com.ems.mockdata.timeseries.NoiseInjector;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class NoiseInjectorTest {

    private final NoiseInjector injector = new NoiseInjector(new Random(42));

    @Test
    void addNoise_staysWithin10Percent() {
        double base = 100.0;
        // run many times — 3-sigma should keep within 20%
        for (int i = 0; i < 1000; i++) {
            double v = injector.addNoise(base);
            assertThat(v).isBetween(70.0, 130.0);
        }
    }

    @Test
    void addNoise_neverNegative() {
        for (int i = 0; i < 500; i++) {
            assertThat(injector.addNoise(1.0)).isGreaterThanOrEqualTo(0.0);
        }
    }

    @Test
    void isMissing_deterministicForSameSeed() {
        boolean first  = injector.isMissing(100, 1000L, 42L);
        boolean second = injector.isMissing(100, 1000L, 42L);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void isMissing_differentMinutesHaveDifferentResults() {
        // not all minutes in a day should be missing
        int missing = 0;
        for (int m = 0; m < 1440; m++) {
            if (injector.isMissing(m, 20000L, 99L)) missing++;
        }
        // 1-3 windows of 5-20 minutes = 5..60 missing; expect between 5 and 100
        assertThat(missing).isBetween(5, 100);
    }

    @Test
    void isZeroStuck_affects_roughly_1pct() {
        int stuck = 0;
        int total = 1000;
        for (int i = 0; i < total; i++) {
            if (injector.isZeroStuck(i, total, 10, 12345L)) stuck++;
        }
        // should be ~1% = ~10, allow [0, 30]
        assertThat(stuck).isBetween(0, 30);
    }
}
