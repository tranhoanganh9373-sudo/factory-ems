package com.ems.mockdata.timeseries;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeseriesGeneratorTest {

    @Test
    void validateTopologyCoverage_passes_whenAllTopologyMetersLoadable() {
        Set<Long> topology = Set.of(1L, 2L, 3L);
        Set<Long> loadable = Set.of(1L, 2L, 3L, 4L, 5L);

        TimeseriesGenerator.validateTopologyCoverage(topology, loadable);
    }

    @Test
    void validateTopologyCoverage_passes_whenTopologyEmpty() {
        TimeseriesGenerator.validateTopologyCoverage(Set.of(), Set.of(1L, 2L));
    }

    @Test
    void validateTopologyCoverage_throws_whenOrphanInTopology() {
        Set<Long> topology = Set.of(1L, 2L, 99L);
        Set<Long> loadable = Set.of(1L, 2L);

        assertThatThrownBy(() ->
            TimeseriesGenerator.validateTopologyCoverage(topology, loadable))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("99")
            .hasMessageContaining("not loadable");
    }

    @Test
    void validateTopologyCoverage_listsAllOrphans_whenMultipleMissing() {
        Set<Long> topology = Set.of(10L, 20L, 30L);
        Set<Long> loadable = Set.of(10L);

        assertThatThrownBy(() ->
            TimeseriesGenerator.validateTopologyCoverage(topology, loadable))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("20")
            .hasMessageContaining("30");
    }
}
