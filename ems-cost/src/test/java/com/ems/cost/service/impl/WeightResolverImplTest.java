package com.ems.cost.service.impl;

import com.ems.cost.service.WeightBasis;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeightResolverImplTest {

    private static final OffsetDateTime START = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
    private static final OffsetDateTime END   = START.plusDays(1);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WeightResolverImpl resolver = new WeightResolverImpl(jdbc);

    @Test
    void fixed_normalizes_to_sum_one() {
        Map<String, Object> raw = Map.of(
                "basis", "FIXED",
                "values", Map.of("10", 0.4, "20", 0.6));

        Map<Long, BigDecimal> out = resolver.resolve(WeightBasis.FIXED, List.of(10L, 20L), raw, START, END);

        assertThat(out).hasSize(2);
        assertThat(out.get(10L).add(out.get(20L))).isEqualByComparingTo("1.000000");
        assertThat(out.get(10L)).isEqualByComparingTo("0.400000");
        assertThat(out.get(20L)).isEqualByComparingTo("0.600000");
    }

    @Test
    void fixed_uses_last_org_remainder_to_make_exact_one() {
        // 1/3 + 1/3 + 1/3 = 0.999999 with 6dp, so last org gets 0.333334
        Map<String, Object> raw = Map.of(
                "basis", "FIXED",
                "values", Map.of("10", 1, "20", 1, "30", 1));

        Map<Long, BigDecimal> out = resolver.resolve(WeightBasis.FIXED, List.of(10L, 20L, 30L), raw, START, END);

        BigDecimal sum = out.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("1");
    }

    @Test
    void fixed_missing_values_falls_back_to_equal_split() {
        // No "values" map → readFixed returns empty → total=0 → equal split
        Map<String, Object> raw = Map.of("basis", "FIXED");

        Map<Long, BigDecimal> out = resolver.resolve(WeightBasis.FIXED, List.of(10L, 20L), raw, START, END);

        assertThat(out).hasSize(2);
        BigDecimal sum = out.get(10L).add(out.get(20L));
        assertThat(sum).isEqualByComparingTo("1");
        // 1/2 = 0.500000
        assertThat(out.get(10L)).isEqualByComparingTo("0.500000");
    }

    @Test
    void empty_orgs_returns_empty_map() {
        Map<Long, BigDecimal> out = resolver.resolve(WeightBasis.FIXED, List.of(), Map.of(), START, END);
        assertThat(out).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void area_reads_org_nodes_and_normalizes() {
        // org 10 → 100 m², org 20 → 300 m² → expect 0.25 / 0.75
        when(jdbc.query(any(String.class), any(ResultSetExtractor.class), eq(10L)))
                .thenReturn(Optional.of(new BigDecimal("100")));
        when(jdbc.query(any(String.class), any(ResultSetExtractor.class), eq(20L)))
                .thenReturn(Optional.of(new BigDecimal("300")));

        Map<Long, BigDecimal> out = resolver.resolve(WeightBasis.AREA, List.of(10L, 20L), Map.of(), START, END);

        assertThat(out).hasSize(2);
        assertThat(out.get(10L)).isEqualByComparingTo("0.250000");
        assertThat(out.get(20L)).isEqualByComparingTo("0.750000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void area_missing_data_falls_back_to_equal_split() {
        when(jdbc.query(any(String.class), any(ResultSetExtractor.class), anyLong()))
                .thenReturn(Optional.empty());

        Map<Long, BigDecimal> out = resolver.resolve(WeightBasis.AREA, List.of(10L, 20L), Map.of(), START, END);

        assertThat(out).hasSize(2);
        assertThat(out.get(10L)).isEqualByComparingTo("0.500000");
        assertThat(out.get(20L)).isEqualByComparingTo("0.500000");
    }

    @Test
    void production_zero_total_falls_back_to_equal_split() {
        when(jdbc.queryForObject(any(String.class), eq(BigDecimal.class), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        Map<Long, BigDecimal> out = resolver.resolve(WeightBasis.PRODUCTION, List.of(10L, 20L), Map.of(), START, END);

        assertThat(out.get(10L)).isEqualByComparingTo("0.500000");
        assertThat(out.get(20L)).isEqualByComparingTo("0.500000");
    }

    @Test
    void production_normalizes_by_total_quantity() {
        Map<Long, BigDecimal> stub = new HashMap<>();
        stub.put(10L, new BigDecimal("200"));
        stub.put(20L, new BigDecimal("800"));
        when(jdbc.queryForObject(any(String.class), eq(BigDecimal.class), any(), any(), any()))
                .thenAnswer(inv -> stub.get((Long) inv.getArgument(2)));

        Map<Long, BigDecimal> out = resolver.resolve(WeightBasis.PRODUCTION, List.of(10L, 20L), Map.of(), START, END);

        assertThat(out.get(10L)).isEqualByComparingTo("0.200000");
        assertThat(out.get(20L)).isEqualByComparingTo("0.800000");
    }
}
