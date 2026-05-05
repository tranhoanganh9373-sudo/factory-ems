package com.ems.mockdata.scenario;

import com.ems.meter.entity.Meter;
import com.ems.meter.entity.MeterTopology;
import com.ems.meter.repository.MeterRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.mockdata.seed.*;
import com.ems.mockdata.timeseries.InfluxBatchWriter;
import com.ems.mockdata.timeseries.InfluxBatchWriter.MinutePoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LegacyScenario implements MockScenario {

    private static final Logger log = LoggerFactory.getLogger(LegacyScenario.class);
    private static final String PREFIX = OrgTreeSeeder.PREFIX;
    private static final String MEASUREMENT = "energy_reading";
    private static final String TAG_KEY = "meter_code";

    private final OrgTreeSeeder orgTreeSeeder;
    private final MeterSeeder meterSeeder;
    private final MeterTopologySeeder meterTopologySeeder;
    private final TariffSeeder tariffSeeder;
    private final ShiftSeeder shiftSeeder;
    private final UserSeeder userSeeder;
    private final MeterRepository meterRepo;
    private final MeterTopologyRepository topoRepo;
    private final InfluxBatchWriter influxBatchWriter;

    public LegacyScenario(OrgTreeSeeder orgTreeSeeder,
                          MeterSeeder meterSeeder,
                          MeterTopologySeeder meterTopologySeeder,
                          TariffSeeder tariffSeeder,
                          ShiftSeeder shiftSeeder,
                          UserSeeder userSeeder,
                          MeterRepository meterRepo,
                          MeterTopologyRepository topoRepo,
                          InfluxBatchWriter influxBatchWriter) {
        this.orgTreeSeeder = orgTreeSeeder;
        this.meterSeeder = meterSeeder;
        this.meterTopologySeeder = meterTopologySeeder;
        this.tariffSeeder = tariffSeeder;
        this.shiftSeeder = shiftSeeder;
        this.userSeeder = userSeeder;
        this.meterRepo = meterRepo;
        this.topoRepo = topoRepo;
        this.influxBatchWriter = influxBatchWriter;
    }

    @Override
    public String name() {
        return "legacy";
    }

    @Override
    @Transactional
    public void seed(ScenarioContext ctx) {
        log.info("--- LegacyScenario: master data seeding (idempotent) ---");
        orgTreeSeeder.seed();
        meterSeeder.seed(ctx.scale());
        meterTopologySeeder.seed();
        tariffSeeder.seed();
        shiftSeeder.seed();
        userSeeder.seed();

        if (!ctx.noInflux()) {
            log.info("--- LegacyScenario: synthetic timeseries (root=1000, children=820 spread, 7 days) ---");
            List<Meter> allMeters = meterRepo.findAllByOrderByCodeAsc().stream()
                .filter(m -> m.getCode().startsWith(PREFIX))
                .toList();

            List<MeterTopology> allTopos = topoRepo.findAll();
            Set<Long> childIds = allTopos.stream()
                .map(MeterTopology::getChildMeterId)
                .collect(Collectors.toSet());
            Set<Long> parentIds = allTopos.stream()
                .map(MeterTopology::getParentMeterId)
                .collect(Collectors.toSet());

            // root = has children but no parent
            List<Meter> rootMeters = allMeters.stream()
                .filter(m -> !childIds.contains(m.getId()) && parentIds.contains(m.getId()))
                .toList();
            // leaf = no children
            List<Meter> leafMeters = allMeters.stream()
                .filter(m -> !parentIds.contains(m.getId()))
                .toList();

            if (rootMeters.isEmpty() || leafMeters.isEmpty()) {
                log.warn("LegacyScenario: could not identify root/leaf meters; skipping timeseries");
                return;
            }

            double rootValue = 1000.0;
            double leafValue = 820.0 / leafMeters.size();

            LocalDate end = ctx.startDate().plusDays(7);
            for (LocalDate d = ctx.startDate(); d.isBefore(end); d = d.plusDays(1)) {
                for (int h = 0; h < 24; h++) {
                    Instant ts = d.atTime(h, 0).toInstant(ZoneOffset.UTC);
                    for (Meter root : rootMeters) {
                        influxBatchWriter.add(new MinutePoint(MEASUREMENT, TAG_KEY,
                            root.getInfluxTagValue(), ts, rootValue));
                    }
                    for (Meter leaf : leafMeters) {
                        influxBatchWriter.add(new MinutePoint(MEASUREMENT, TAG_KEY,
                            leaf.getInfluxTagValue(), ts, leafValue));
                    }
                }
            }
            influxBatchWriter.flush();
            log.info("LegacyScenario timeseries written: root={} kWh/hr, {} leaves={} kWh/hr each → residual ~18%",
                rootValue, leafMeters.size(), String.format("%.2f", leafValue));
        } else {
            log.info("--- LegacyScenario: skipping timeseries (no-influx=true) ---");
        }

        log.info("LegacyScenario seeding complete");
    }
}
