package com.ems.mockdata.scenario;

import com.ems.meter.entity.*;
import com.ems.meter.repository.MeterRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.mockdata.seed.OrgTreeSeeder;
import com.ems.mockdata.seed.ShiftSeeder;
import com.ems.mockdata.seed.TariffSeeder;
import com.ems.mockdata.seed.UserSeeder;
import com.ems.mockdata.timeseries.InfluxBatchWriter;
import com.ems.mockdata.timeseries.InfluxBatchWriter.MinutePoint;
import com.ems.orgtree.entity.OrgNode;
import com.ems.orgtree.repository.OrgNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Component
public class WithPvScenario implements MockScenario {

    private static final Logger log = LoggerFactory.getLogger(WithPvScenario.class);
    private static final String PREFIX = OrgTreeSeeder.PREFIX;
    private static final String MEASUREMENT = "energy_reading";
    private static final String TAG_KEY = "meter_code";

    private final OrgTreeSeeder orgTreeSeeder;
    private final TariffSeeder tariffSeeder;
    private final ShiftSeeder shiftSeeder;
    private final UserSeeder userSeeder;
    private final MeterRepository meterRepo;
    private final MeterTopologyRepository topoRepo;
    private final OrgNodeRepository orgNodeRepo;
    private final InfluxBatchWriter influxBatchWriter;
    private final JdbcTemplate jdbc;

    public WithPvScenario(OrgTreeSeeder orgTreeSeeder,
                          TariffSeeder tariffSeeder,
                          ShiftSeeder shiftSeeder,
                          UserSeeder userSeeder,
                          MeterRepository meterRepo,
                          MeterTopologyRepository topoRepo,
                          OrgNodeRepository orgNodeRepo,
                          InfluxBatchWriter influxBatchWriter,
                          JdbcTemplate jdbc) {
        this.orgTreeSeeder = orgTreeSeeder;
        this.tariffSeeder = tariffSeeder;
        this.shiftSeeder = shiftSeeder;
        this.userSeeder = userSeeder;
        this.meterRepo = meterRepo;
        this.topoRepo = topoRepo;
        this.orgNodeRepo = orgNodeRepo;
        this.influxBatchWriter = influxBatchWriter;
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "with-pv";
    }

    @Override
    @Transactional
    public void seed(ScenarioContext ctx) {
        if (meterRepo.existsByCode(PREFIX + "PV-GRID-IMPORT")) {
            log.info("WithPvScenario already seeded, skipping");
            return;
        }

        log.info("--- WithPvScenario: seeding org tree ---");
        orgTreeSeeder.seed();

        log.info("--- WithPvScenario: creating 7 PV-shape meters ---");
        Long elecTypeId = jdbc.queryForObject(
            "SELECT id FROM energy_types WHERE code = ?", Long.class, "ELEC");

        List<OrgNode> allNodes = orgNodeRepo.findAll().stream()
            .filter(n -> n.getCode().startsWith(PREFIX))
            .toList();
        if (allNodes.isEmpty()) {
            throw new IllegalStateException("OrgTree not seeded yet");
        }
        OrgNode factoryNode = allNodes.stream()
            .filter(n -> "FACTORY".equals(n.getNodeType()))
            .findFirst()
            .orElseGet(() -> allNodes.get(0));

        Meter gridImport = createMeter(PREFIX + "PV-GRID-IMPORT", "并网进线表", elecTypeId, factoryNode.getId(),
            MeterRole.GRID_TIE, EnergySource.GRID, FlowDirection.IMPORT);
        createMeter(PREFIX + "PV-GRID-EXPORT", "并网出线表", elecTypeId, factoryNode.getId(),
            MeterRole.GRID_TIE, EnergySource.GRID, FlowDirection.EXPORT);
        createMeter(PREFIX + "PV-SOLAR-MAIN", "光伏主表", elecTypeId, factoryNode.getId(),
            MeterRole.GENERATE, EnergySource.SOLAR, FlowDirection.IMPORT);

        List<Meter> consumeMeters = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            consumeMeters.add(createMeter(
                PREFIX + "PV-CONSUME-00" + i, "消费表-" + i, elecTypeId, factoryNode.getId(),
                MeterRole.CONSUME, EnergySource.GRID, FlowDirection.IMPORT));
        }

        log.info("--- WithPvScenario: wiring topology (5 CONSUME -> PV-GRID-IMPORT) ---");
        for (Meter c : consumeMeters) {
            MeterTopology t = new MeterTopology();
            t.setChildMeterId(c.getId());
            t.setParentMeterId(gridImport.getId());
            topoRepo.save(t);
        }

        log.info("--- WithPvScenario: seeding tariff / shift / user ---");
        tariffSeeder.seed();
        shiftSeeder.seed();
        userSeeder.seed();

        if (!ctx.noInflux()) {
            log.info("--- WithPvScenario: generating 7-day hourly timeseries ---");
            LocalDate end = ctx.startDate().plusDays(7);
            for (LocalDate d = ctx.startDate(); d.isBefore(end); d = d.plusDays(1)) {
                for (int h = 0; h < 24; h++) {
                    Instant ts = d.atTime(h, 0).toInstant(ZoneOffset.UTC);
                    boolean solarHour = h >= 11 && h <= 15;
                    double solar   = solarHour ? 600.0 : 0.0;
                    double export_ = solarHour ? 100.0 : 0.0;
                    double import_ = solarHour ? 0.0 : 500.0;

                    for (Meter c : consumeMeters) {
                        influxBatchWriter.add(new MinutePoint(MEASUREMENT, TAG_KEY, c.getInfluxTagValue(), ts, 100.0));
                    }
                    influxBatchWriter.add(new MinutePoint(MEASUREMENT, TAG_KEY,
                        PREFIX + "PV-SOLAR-MAIN", ts, solar));
                    influxBatchWriter.add(new MinutePoint(MEASUREMENT, TAG_KEY,
                        PREFIX + "PV-GRID-EXPORT", ts, export_));
                    influxBatchWriter.add(new MinutePoint(MEASUREMENT, TAG_KEY,
                        PREFIX + "PV-GRID-IMPORT", ts, import_));
                }
            }
            influxBatchWriter.flush();
        } else {
            log.info("--- WithPvScenario: skipping timeseries (no-influx=true) ---");
        }

        log.info("WithPvScenario seeding complete");
    }

    private Meter createMeter(String code, String name, Long energyTypeId, Long orgNodeId,
                              MeterRole role, EnergySource source, FlowDirection dir) {
        Meter m = new Meter();
        m.setCode(code);
        m.setName(name);
        m.setEnergyTypeId(energyTypeId);
        m.setOrgNodeId(orgNodeId);
        m.setInfluxMeasurement(MEASUREMENT);
        m.setInfluxTagKey(TAG_KEY);
        m.setInfluxTagValue(code);
        m.setEnabled(true);
        m.setRole(role);
        m.setEnergySource(source);
        m.setFlowDirection(dir);
        return meterRepo.save(m);
    }
}
