package com.ems.mockdata.timeseries;

import com.ems.meter.entity.Meter;
import com.ems.meter.entity.MeterTopology;
import com.ems.meter.repository.MeterRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.mockdata.config.ScaleProfile;
import com.ems.mockdata.timeseries.RollupBatchWriter.DailyRow;
import com.ems.mockdata.timeseries.RollupBatchWriter.HourlyRow;
import com.ems.mockdata.timeseries.RollupBatchWriter.MonthlyRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates timeseries generation:
 * 1. Loads meters + topology from DB
 * 2. For each meter, for each day/hour/minute: profile + noise -> Influx point
 * 3. Aggregates per-hour stats -> RollupHourly
 * 4. Aggregates hourly -> daily -> monthly
 * 5. Applies ConservationEnforcer to parent meters
 */
@Component
public class TimeseriesGenerator {

    private static final Logger log = LoggerFactory.getLogger(TimeseriesGenerator.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    // base kWh per minute at full load (electric). Water/steam scaled separately.
    private static final double BASE_ELEC_KWH_PER_MIN = 5.0;
    private static final double BASE_WATER_M3_PER_MIN = 0.08;
    private static final double BASE_STEAM_T_PER_MIN  = 0.02;

    private final MeterRepository meterRepo;
    private final MeterTopologyRepository topoRepo;
    private final JdbcTemplate jdbc;

    public TimeseriesGenerator(MeterRepository meterRepo,
                               MeterTopologyRepository topoRepo,
                               JdbcTemplate jdbc) {
        this.meterRepo = meterRepo;
        this.topoRepo = topoRepo;
        this.jdbc = jdbc;
    }

    public void generate(ScaleProfile scale, LocalDate startDate, LocalDate endDate,
                         long seed, boolean noInflux,
                         InfluxBatchWriter influxWriter,
                         RollupBatchWriter rollupWriter) {

        Random rng = new Random(seed);
        ProfileGenerator profile = new ProfileGenerator();
        NoiseInjector noise = new NoiseInjector(new Random(seed + 1));

        // load meters
        List<Meter> meters = meterRepo.findAllByOrderByCodeAsc().stream()
            .filter(m -> m.getCode().startsWith("MOCK-"))
            .toList();
        if (meters.isEmpty()) {
            log.warn("No MOCK- meters found. Run MeterSeeder first.");
            return;
        }
        log.info("Generating timeseries for {} meters, {} to {}", meters.size(), startDate, endDate);

        // build topology maps
        Map<Long, List<Long>> parentToChildren = new HashMap<>(); // parentId -> [childIds]
        Map<Long, Long> childToParent = new HashMap<>();          // childId -> parentId
        for (MeterTopology t : topoRepo.findAll()) {
            parentToChildren.computeIfAbsent(t.getParentMeterId(), k -> new ArrayList<>())
                .add(t.getChildMeterId());
            childToParent.put(t.getChildMeterId(), t.getParentMeterId());
        }

        Set<Long> parentMeterIds = new HashSet<>(parentToChildren.keySet());
        Set<Long> leafMeterIds = meters.stream()
            .map(Meter::getId)
            .filter(id -> !parentMeterIds.contains(id))
            .collect(Collectors.toSet());

        // Bottom-up order: a parent only computes after every parent-child of its has been resolved.
        // Without this, a level-3 parent (e.g. MAIN with sub-mains as children) reads sub-main
        // accumulators before they are populated, sums to 0, and fails conservation 100% of the time.
        List<Long> parentMeterIdsBottomUp = new ArrayList<>();
        {
            Set<Long> remaining = new HashSet<>(parentMeterIds);
            while (!remaining.isEmpty()) {
                List<Long> ready = remaining.stream()
                    .filter(pid -> parentToChildren.getOrDefault(pid, List.of()).stream()
                        .noneMatch(remaining::contains))
                    .toList();
                if (ready.isEmpty()) {
                    log.warn("Topology contains a cycle or self-reference; remaining={}", remaining);
                    parentMeterIdsBottomUp.addAll(remaining);
                    break;
                }
                parentMeterIdsBottomUp.addAll(ready);
                ready.forEach(remaining::remove);
            }
        }

        // energy type lookup
        Map<Long, String> etCodeById = new HashMap<>();
        jdbc.query("SELECT id, code FROM energy_types", rs -> {
            etCodeById.put(rs.getLong("id"), rs.getString("code"));
        });

        ConservationEnforcer conservation = new ConservationEnforcer(rng, parentToChildren);

        // meter index map for zero-stuck detection
        Map<Long, Integer> meterIndex = new HashMap<>();
        for (int i = 0; i < meters.size(); i++) meterIndex.put(meters.get(i).getId(), i);

        // iterate day by day
        LocalDate cursor = startDate;
        String currentMonth = null;

        // per-meter daily accumulators: meterId -> [sum, count, max, min]
        Map<Long, double[]> dailyAcc = new HashMap<>();
        // per-meter monthly accumulators: meterId -> [sum, count, max, min]
        Map<Long, double[]> monthlyAcc = new HashMap<>();

        // plan negative residuals for each month+parent
        LocalDate monthCursor = startDate.withDayOfMonth(1);
        while (!monthCursor.isAfter(endDate)) {
            ZonedDateTime monthZdt = monthCursor.atStartOfDay(SHANGHAI);
            OffsetDateTime monthOdt = monthZdt.toOffsetDateTime();
            for (Long pid : parentMeterIds) {
                conservation.planNegativeResiduals(pid, monthOdt);
            }
            monthCursor = monthCursor.plusMonths(1);
        }

        while (!cursor.isAfter(endDate.minusDays(1))) {
            String yearMonth = cursor.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            boolean newMonth = !yearMonth.equals(currentMonth);

            if (newMonth && currentMonth != null) {
                // flush monthly accumulators for previous month
                flushMonthly(meters, monthlyAcc, etCodeById, currentMonth, rollupWriter);
                monthlyAcc.clear();
            }
            currentMonth = yearMonth;

            // reset daily accumulators
            dailyAcc.clear();
            for (Meter m : meters) {
                dailyAcc.put(m.getId(), new double[]{0, 0, Double.MIN_VALUE, Double.MAX_VALUE});
            }

            // hour-by-hour for this day
            for (int hour = 0; hour < 24; hour++) {
                ZonedDateTime hourZdt = cursor.atTime(hour, 0).atZone(SHANGHAI);
                OffsetDateTime hourOdt = hourZdt.toOffsetDateTime();

                // per-meter per-hour accumulators: meterId -> [sum, count, max, min]
                Map<Long, double[]> hourAcc = new HashMap<>();
                for (Meter m : meters) {
                    hourAcc.put(m.getId(), new double[]{0, 0, Double.MIN_VALUE, Double.MAX_VALUE});
                }

                // generate minute-by-minute for each LEAF meter
                for (Meter m : meters) {
                    if (parentMeterIds.contains(m.getId())) continue; // will be set by conservation

                    String etCode = etCodeById.getOrDefault(m.getEnergyTypeId(), "ELEC");
                    double basePerMin = basePerMinute(etCode);
                    int mIdx = meterIndex.getOrDefault(m.getId(), 0);
                    long dayHash = cursor.toEpochDay();

                    // zero-stuck check for this hour
                    boolean stuck = noise.isZeroStuck(mIdx, meters.size(), hour, dayHash);

                    for (int min = 0; min < 60; min++) {
                        int minuteOfDay = hour * 60 + min;
                        LocalDateTime ldt = cursor.atTime(hour, min);

                        double value;
                        if (stuck) {
                            value = 0.0;
                        } else if (noise.isMissing(minuteOfDay, dayHash, m.getId())) {
                            // missing: skip Influx write, don't count in rollup
                            continue;
                        } else {
                            double factor = profileFactor(profile, etCode, ldt);
                            double base = basePerMin * factor;
                            value = noise.addNoise(base);
                        }

                        if (!noInflux) {
                            Instant ts = ldt.atZone(SHANGHAI).toInstant();
                            influxWriter.add(new InfluxBatchWriter.MinutePoint(
                                m.getInfluxMeasurement(), m.getInfluxTagKey(),
                                m.getInfluxTagValue(), ts, value));
                        }

                        // accumulate for hourly rollup
                        double[] ha = hourAcc.get(m.getId());
                        ha[0] += value;          // sum
                        ha[1]++;                 // count
                        ha[2] = Math.max(ha[2], value); // max
                        ha[3] = Math.min(ha[3], value); // min
                    }
                }

                // compute parent values via conservation (bottom-up so multi-level topologies resolve)
                for (Long pid : parentMeterIdsBottomUp) {
                    Map<Long, Double> childSums = new HashMap<>();
                    for (Long cid : parentToChildren.getOrDefault(pid, List.of())) {
                        double[] ha = hourAcc.get(cid);
                        if (ha != null && ha[1] > 0) childSums.put(cid, ha[0]);
                    }
                    double parentSum = conservation.computeParentSum(pid, hourOdt, childSums);
                    double[] pa = hourAcc.get(pid);
                    if (pa != null) {
                        pa[0] = parentSum;
                        pa[1] = 1; // synthetic count
                        pa[2] = parentSum;
                        pa[3] = parentSum;
                    }
                }

                // write hourly rollup rows + accumulate daily
                for (Meter m : meters) {
                    double[] ha = hourAcc.get(m.getId());
                    if (ha == null || ha[1] == 0) continue;
                    double sum = ha[0];
                    int cnt = (int) ha[1];
                    double max = ha[2] == Double.MIN_VALUE ? sum : ha[2];
                    double min = ha[3] == Double.MAX_VALUE ? sum : ha[3];
                    double avg = sum / cnt;

                    rollupWriter.addHourly(new HourlyRow(
                        m.getId(), m.getOrgNodeId(), hourOdt, sum, avg, max, min, cnt));

                    // accumulate daily
                    double[] da = dailyAcc.computeIfAbsent(m.getId(),
                        k -> new double[]{0, 0, Double.MIN_VALUE, Double.MAX_VALUE});
                    da[0] += sum;
                    da[1] += cnt;
                    da[2] = Math.max(da[2], max);
                    da[3] = Math.min(da[3], min);
                }
            }

            // write daily rollup rows + accumulate monthly
            for (Meter m : meters) {
                double[] da = dailyAcc.get(m.getId());
                if (da == null || da[1] == 0) continue;
                double sum = da[0];
                int cnt = (int) da[1];
                double max = da[2] == Double.MIN_VALUE ? sum : da[2];
                double min = da[3] == Double.MAX_VALUE ? sum : da[3];
                double avg = sum / cnt;

                rollupWriter.addDaily(new DailyRow(
                    m.getId(), m.getOrgNodeId(), cursor, sum, avg, max, min, cnt));

                double[] ma = monthlyAcc.computeIfAbsent(m.getId(),
                    k -> new double[]{0, 0, Double.MIN_VALUE, Double.MAX_VALUE});
                ma[0] += sum;
                ma[1] += cnt;
                ma[2] = Math.max(ma[2], max);
                ma[3] = Math.min(ma[3], min);
            }

            cursor = cursor.plusDays(1);
        }

        // flush final month
        if (currentMonth != null) {
            flushMonthly(meters, monthlyAcc, etCodeById, currentMonth, rollupWriter);
        }

        rollupWriter.flushAll();
        if (!noInflux) influxWriter.flush();

        // write conservation sidecar
        conservation.writeSidecar(new File("target/mock-data-conservation-sidecar.json"));

        log.info("Timeseries generation complete");
    }

    private void flushMonthly(List<Meter> meters, Map<Long, double[]> monthlyAcc,
                              Map<Long, String> etCodeById, String yearMonth,
                              RollupBatchWriter rollupWriter) {
        for (Meter m : meters) {
            double[] ma = monthlyAcc.get(m.getId());
            if (ma == null || ma[1] == 0) continue;
            double sum = ma[0];
            int cnt = (int) ma[1];
            double max = ma[2] == Double.MIN_VALUE ? sum : ma[2];
            double min = ma[3] == Double.MAX_VALUE ? sum : ma[3];
            double avg = sum / cnt;
            rollupWriter.addMonthly(new MonthlyRow(
                m.getId(), m.getOrgNodeId(), yearMonth, sum, avg, max, min, cnt));
        }
    }

    private double basePerMinute(String etCode) {
        return switch (etCode) {
            case "ELEC"  -> BASE_ELEC_KWH_PER_MIN;
            case "WATER" -> BASE_WATER_M3_PER_MIN;
            case "STEAM" -> BASE_STEAM_T_PER_MIN;
            default      -> BASE_ELEC_KWH_PER_MIN * 0.5;
        };
    }

    private double profileFactor(ProfileGenerator p, String etCode, LocalDateTime ldt) {
        return switch (etCode) {
            case "ELEC"  -> p.electricFactor(ldt);
            case "WATER" -> p.waterFactor(ldt);
            default      -> p.steamFactor(ldt);
        };
    }
}
