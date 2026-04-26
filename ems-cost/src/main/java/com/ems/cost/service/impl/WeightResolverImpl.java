package com.ems.cost.service.impl;

import com.ems.cost.service.WeightBasis;
import com.ems.cost.service.WeightResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class WeightResolverImpl implements WeightResolver {

    private static final Logger log = LoggerFactory.getLogger(WeightResolverImpl.class);

    private final JdbcTemplate jdbc;

    public WeightResolverImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, BigDecimal> resolve(WeightBasis basis,
                                         List<Long> targetOrgIds,
                                         Map<String, Object> rawWeights,
                                         OffsetDateTime periodStart,
                                         OffsetDateTime periodEnd) {
        if (targetOrgIds == null || targetOrgIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, BigDecimal> raw = switch (basis) {
            case FIXED      -> readFixed(targetOrgIds, rawWeights);
            case AREA       -> readArea(targetOrgIds);
            case HEADCOUNT  -> readHeadcount(targetOrgIds);
            case PRODUCTION -> readProduction(targetOrgIds, periodStart, periodEnd);
        };

        return normalize(targetOrgIds, raw, basis);
    }

    private Map<Long, BigDecimal> readFixed(List<Long> orgs, Map<String, Object> raw) {
        if (raw == null) raw = Map.of();
        Object values = raw.get("values");
        Map<Long, BigDecimal> out = new HashMap<>();
        if (values instanceof Map<?, ?> m) {
            for (Long org : orgs) {
                Object v = m.get(String.valueOf(org));
                if (v == null) v = m.get(org);  // tolerate Long key
                if (v != null) out.put(org, new BigDecimal(v.toString()));
            }
        }
        return out;
    }

    private Map<Long, BigDecimal> readArea(List<Long> orgs) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Long org : orgs) {
            Optional<BigDecimal> area = jdbc.query(
                    "SELECT area_m2 FROM org_nodes WHERE id = ?",
                    rs -> rs.next() ? Optional.ofNullable(rs.getBigDecimal(1)) : Optional.empty(),
                    org);
            area.ifPresent(v -> out.put(org, v));
        }
        return out;
    }

    private Map<Long, BigDecimal> readHeadcount(List<Long> orgs) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Long org : orgs) {
            Optional<Integer> hc = jdbc.query(
                    "SELECT headcount FROM org_nodes WHERE id = ?",
                    rs -> {
                        if (!rs.next()) return Optional.<Integer>empty();
                        int v = rs.getInt(1);
                        return rs.wasNull() ? Optional.<Integer>empty() : Optional.of(v);
                    },
                    org);
            hc.ifPresent(v -> out.put(org, new BigDecimal(v)));
        }
        return out;
    }

    private Map<Long, BigDecimal> readProduction(List<Long> orgs, OffsetDateTime start, OffsetDateTime end) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Long org : orgs) {
            BigDecimal sum = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(quantity), 0) FROM production_entries " +
                    "WHERE org_node_id = ? AND entry_date >= ?::date AND entry_date < ?::date",
                    BigDecimal.class,
                    org, start.toLocalDate(), end.toLocalDate());
            if (sum != null && sum.signum() > 0) out.put(org, sum);
        }
        return out;
    }

    private Map<Long, BigDecimal> normalize(List<Long> orgs, Map<Long, BigDecimal> raw, WeightBasis basis) {
        BigDecimal total = raw.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        // fallback: if no data for any org → equal weights
        if (total.signum() == 0) {
            log.warn("No usable weight data for basis={} orgs={}; falling back to equal split", basis, orgs);
            return equalSplit(orgs);
        }

        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        BigDecimal acc = BigDecimal.ZERO;
        for (int i = 0; i < orgs.size(); i++) {
            Long org = orgs.get(i);
            BigDecimal v = raw.getOrDefault(org, BigDecimal.ZERO);
            BigDecimal w;
            if (i == orgs.size() - 1) {
                // last org gets the remainder to ensure sum = 1.0000 exactly
                w = BigDecimal.ONE.subtract(acc).setScale(6, RoundingMode.HALF_UP);
            } else {
                w = v.divide(total, 6, RoundingMode.HALF_UP);
                acc = acc.add(w);
            }
            out.put(org, w);
        }
        return out;
    }

    private Map<Long, BigDecimal> equalSplit(List<Long> orgs) {
        BigDecimal one = BigDecimal.ONE.divide(BigDecimal.valueOf(orgs.size()), 6, RoundingMode.HALF_UP);
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        BigDecimal acc = BigDecimal.ZERO;
        for (int i = 0; i < orgs.size(); i++) {
            Long org = orgs.get(i);
            BigDecimal w = (i == orgs.size() - 1)
                    ? BigDecimal.ONE.subtract(acc).setScale(6, RoundingMode.HALF_UP)
                    : one;
            acc = acc.add(w);
            out.put(org, w);
        }
        return out;
    }
}
