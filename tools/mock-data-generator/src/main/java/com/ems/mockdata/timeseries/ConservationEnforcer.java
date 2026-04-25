package com.ems.mockdata.timeseries;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * For each parent meter: parent_value = sum(children) * (1 + residualPct).
 * residualPct in [0.05, 0.15].
 * Injects 1-2 negative-residual hours per month (residualPct in [-0.05, -0.02])
 * so the downstream cost-allocation clamp path is exercised.
 * Writes a sidecar JSON listing injected negative-residual timestamps.
 */
public class ConservationEnforcer {

    private static final Logger log = LoggerFactory.getLogger(ConservationEnforcer.class);

    private final Random rng;
    /** key = parentMeterId, value = list of child meterIds */
    private final Map<Long, List<Long>> topology;
    /** key = parentMeterId, value = set of hour OffsetDateTimes with negative residual */
    private final Map<Long, Set<OffsetDateTime>> negativeResidualHours = new HashMap<>();

    private static final double RESIDUAL_MIN = 0.05;
    private static final double RESIDUAL_MAX = 0.15;
    private static final double NEG_RESIDUAL_MIN = -0.05;
    private static final double NEG_RESIDUAL_MAX = -0.02;

    public ConservationEnforcer(Random rng, Map<Long, List<Long>> topology) {
        this.rng = rng;
        this.topology = topology;
    }

    /**
     * Pre-compute which hours will have negative residual for this month.
     * Call once per month per parent meter before generating that month's data.
     */
    public void planNegativeResiduals(Long parentMeterId, OffsetDateTime monthStart) {
        Set<OffsetDateTime> negHours = negativeResidualHours
            .computeIfAbsent(parentMeterId, k -> new HashSet<>());

        // inject 1-2 negative-residual hours per month
        int count = 1 + rng.nextInt(2);
        // spread across the month (days 1..28)
        Set<Integer> usedHours = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int dayOffset = rng.nextInt(28);
            int hour = rng.nextInt(24);
            int key = dayOffset * 24 + hour;
            if (!usedHours.contains(key)) {
                usedHours.add(key);
                negHours.add(monthStart.plusDays(dayOffset).withHour(hour)
                    .withMinute(0).withSecond(0).withNano(0));
            }
        }
    }

    /**
     * Returns the residual multiplier for (parentMeterId, hourTs).
     * Most hours: random in [RESIDUAL_MIN, RESIDUAL_MAX].
     * Pre-planned negative hours: random in [NEG_RESIDUAL_MIN, NEG_RESIDUAL_MAX].
     */
    public double residualFor(Long parentMeterId, OffsetDateTime hourTs) {
        Set<OffsetDateTime> neg = negativeResidualHours.getOrDefault(parentMeterId, Set.of());
        if (neg.contains(hourTs)) {
            return NEG_RESIDUAL_MIN + rng.nextDouble() * (NEG_RESIDUAL_MAX - NEG_RESIDUAL_MIN);
        }
        return RESIDUAL_MIN + rng.nextDouble() * (RESIDUAL_MAX - RESIDUAL_MIN);
    }

    /**
     * Given child hourly sums (meterId -> sumValue) and the parent meterId,
     * returns the parent's adjusted sum = childrenSum * (1 + residual).
     */
    public double computeParentSum(Long parentMeterId, OffsetDateTime hourTs,
                                   Map<Long, Double> childSums) {
        List<Long> children = topology.getOrDefault(parentMeterId, List.of());
        double childrenSum = children.stream()
            .mapToDouble(cid -> childSums.getOrDefault(cid, 0.0))
            .sum();
        double residual = residualFor(parentMeterId, hourTs);
        return childrenSum * (1.0 + residual);
    }

    public Map<Long, Set<OffsetDateTime>> getNegativeResidualHours() {
        return Collections.unmodifiableMap(negativeResidualHours);
    }

    /** Write sidecar JSON for verification. */
    public void writeSidecar(File outputFile) {
        try {
            ObjectMapper om = new ObjectMapper();
            ArrayNode root = om.createArrayNode();
            DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
            negativeResidualHours.forEach((meterId, hours) -> {
                for (OffsetDateTime h : hours) {
                    ObjectNode entry = om.createObjectNode();
                    entry.put("parentMeterId", meterId);
                    entry.put("hourTs", fmt.format(h));
                    entry.put("type", "negative_residual");
                    root.add(entry);
                }
            });
            outputFile.getParentFile().mkdirs();
            om.writerWithDefaultPrettyPrinter().writeValue(outputFile, root);
            log.info("Wrote conservation sidecar to {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not write conservation sidecar: {}", e.getMessage());
        }
    }
}
