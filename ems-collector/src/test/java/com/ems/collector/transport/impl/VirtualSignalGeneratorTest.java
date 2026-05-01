package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VirtualSignalGeneratorTest {
    private final VirtualSignalGenerator gen = new VirtualSignalGenerator();

    @Test
    void constantReturnsValue() {
        var p = new VirtualPoint("p", VirtualMode.CONSTANT, Map.of("value", 42.5), null);
        assertThat((Double) gen.generate(p, Instant.now())).isEqualTo(42.5);
    }

    @Test
    void sineMatchesFormulaAtKeyPhases() {
        var p = new VirtualPoint("p", VirtualMode.SINE,
            Map.of("amplitude", 10.0, "periodSec", 60.0, "offset", 5.0), null);
        var t0 = Instant.parse("2026-04-30T00:00:00Z");        // sin(0) = 0 → 5
        var tQ = Instant.parse("2026-04-30T00:00:15Z");        // sin(π/2) = 1 → 15
        assertThat((Double) gen.generate(p, t0)).isCloseTo(5.0, within(1e-6));
        assertThat((Double) gen.generate(p, tQ)).isCloseTo(15.0, within(1e-6));
    }

    @Test
    void randomWalkStaysInBounds() {
        var p = new VirtualPoint("p", VirtualMode.RANDOM_WALK,
            Map.of("min", 0.0, "max", 100.0, "maxStep", 1.0, "start", 50.0), null);
        double prev = 50.0;
        for (int i = 0; i < 1000; i++) {
            var v = (Double) gen.generate(p, Instant.now().plusSeconds(i));
            assertThat(v).isBetween(0.0, 100.0);
            assertThat(Math.abs(v - prev)).isLessThanOrEqualTo(1.0 + 1e-9);
            prev = v;
        }
    }

    @Test
    void calendarCurveDiffersWeekendFromWeekday() {
        var p = new VirtualPoint("p", VirtualMode.CALENDAR_CURVE,
            Map.of("weekdayPeak", 100.0, "weekendPeak", 30.0, "peakHour", 9.0), null);
        var weekday9am = Instant.parse("2026-05-04T09:00:00Z");  // Monday 09:00 UTC
        var weekend9am = Instant.parse("2026-05-02T09:00:00Z");  // Saturday 09:00 UTC
        var w = (Double) gen.generate(p, weekday9am);
        var s = (Double) gen.generate(p, weekend9am);
        assertThat(w).isGreaterThan(s);
    }
}
