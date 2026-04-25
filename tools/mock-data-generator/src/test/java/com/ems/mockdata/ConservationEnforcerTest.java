package com.ems.mockdata;

import com.ems.mockdata.timeseries.ConservationEnforcer;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ConservationEnforcerTest {

    private static final Long PARENT = 1L;
    private static final Long CHILD1 = 2L;
    private static final Long CHILD2 = 3L;

    private ConservationEnforcer buildEnforcer(long seed) {
        Map<Long, List<Long>> topo = Map.of(PARENT, List.of(CHILD1, CHILD2));
        return new ConservationEnforcer(new Random(seed), topo);
    }

    @Test
    void residualFor_normalHour_inExpectedRange() {
        ConservationEnforcer ce = buildEnforcer(42);
        OffsetDateTime hour = OffsetDateTime.of(2026, 3, 10, 9, 0, 0, 0, ZoneOffset.ofHours(8));
        double r = ce.residualFor(PARENT, hour);
        assertThat(r).isBetween(0.05, 0.15);
    }

    @Test
    void planNegativeResiduals_and_residualFor_negativeHour() {
        ConservationEnforcer ce = buildEnforcer(42);
        OffsetDateTime monthStart = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
        ce.planNegativeResiduals(PARENT, monthStart);

        Map<Long, java.util.Set<OffsetDateTime>> neg = ce.getNegativeResidualHours();
        assertThat(neg).containsKey(PARENT);
        assertThat(neg.get(PARENT)).isNotEmpty();

        // all planned negative hours should return negative residual
        for (OffsetDateTime negHour : neg.get(PARENT)) {
            double r = ce.residualFor(PARENT, negHour);
            assertThat(r).isNegative();
        }
    }

    @Test
    void computeParentSum_exceedsChildrenSum() {
        ConservationEnforcer ce = buildEnforcer(42);
        OffsetDateTime hour = OffsetDateTime.of(2026, 4, 10, 14, 0, 0, 0, ZoneOffset.ofHours(8));
        Map<Long, Double> childSums = Map.of(CHILD1, 100.0, CHILD2, 80.0);
        double parentSum = ce.computeParentSum(PARENT, hour, childSums);
        // parent = (100+80) * (1 + residual[0.05..0.15]) -> [189, 207]
        assertThat(parentSum).isBetween(185.0, 210.0);
    }

    @Test
    void computeParentSum_negativeResidual_belowChildrenSum() {
        ConservationEnforcer ce = buildEnforcer(42);
        OffsetDateTime monthStart = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
        ce.planNegativeResiduals(PARENT, monthStart);

        for (OffsetDateTime negHour : ce.getNegativeResidualHours().get(PARENT)) {
            Map<Long, Double> childSums = Map.of(CHILD1, 100.0, CHILD2, 80.0);
            double parentSum = ce.computeParentSum(PARENT, negHour, childSums);
            // negative residual -> parent < children sum
            assertThat(parentSum).isLessThan(180.0);
        }
    }
}
