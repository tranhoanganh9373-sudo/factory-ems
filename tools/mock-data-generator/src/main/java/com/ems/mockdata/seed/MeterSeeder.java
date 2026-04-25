package com.ems.mockdata.seed;

import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import com.ems.mockdata.config.ScaleProfile;
import com.ems.orgtree.entity.OrgNode;
import com.ems.orgtree.repository.OrgNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates meters distributed across org nodes.
 * influx_measurement = "energy_reading", influx_tag_key = "meter_code", influx_tag_value = meter.code.
 */
@Component
public class MeterSeeder {

    private static final Logger log = LoggerFactory.getLogger(MeterSeeder.class);
    private static final String PREFIX = OrgTreeSeeder.PREFIX;
    private static final String MEASUREMENT = "energy_reading";
    private static final String TAG_KEY = "meter_code";

    // energy_type IDs from V1.2.0 seed: ELEC=1, WATER=2, STEAM=3
    // In actual DB these are auto-generated; we look them up by code.
    private final MeterRepository meterRepo;
    private final OrgNodeRepository orgNodeRepo;
    private final JdbcTemplate jdbc;

    public MeterSeeder(MeterRepository meterRepo, OrgNodeRepository orgNodeRepo,
                       JdbcTemplate jdbc) {
        this.meterRepo = meterRepo;
        this.orgNodeRepo = orgNodeRepo;
        this.jdbc = jdbc;
    }

    @Transactional
    public void seed(ScaleProfile scale) {
        if (meterRepo.existsByCode(PREFIX + "M-ELEC-001")) {
            log.info("Meters already seeded, skipping");
            return;
        }
        log.info("Seeding {} meters (scale={})...", scale.getTotalMeters(), scale);

        Long elecTypeId = findEnergyTypeId("ELEC");
        Long waterTypeId = findEnergyTypeId("WATER");
        Long steamTypeId = findEnergyTypeId("STEAM");

        List<OrgNode> allNodes = orgNodeRepo.findAll().stream()
            .filter(n -> n.getCode().startsWith(PREFIX))
            .toList();
        if (allNodes.isEmpty()) {
            throw new IllegalStateException("OrgTree not seeded yet; run OrgTreeSeeder first");
        }

        // find workshop-level nodes for distribution
        List<OrgNode> workshops = allNodes.stream()
            .filter(n -> "WORKSHOP".equals(n.getNodeType()))
            .toList();
        List<OrgNode> processNodes = allNodes.stream()
            .filter(n -> "PROCESS".equals(n.getNodeType()))
            .toList();
        OrgNode utilNode = allNodes.stream()
            .filter(n -> "UTIL".equals(n.getNodeType()) || PREFIX.concat("UTIL").equals(n.getCode()))
            .findFirst()
            .orElseGet(() -> allNodes.get(0));
        OrgNode factoryNode = allNodes.stream()
            .filter(n -> "FACTORY".equals(n.getNodeType()))
            .findFirst()
            .orElseGet(() -> allNodes.get(0));

        int created = 0;
        // 1 main incomer at factory level (electric)
        createMeter(PREFIX + "M-ELEC-MAIN", "总进线电表", elecTypeId, factoryNode.getId());
        created++;

        // electric meters distributed across workshops/processes
        int elecCount = scale.getElectricMeters() - 1; // already created main
        for (int i = 1; i <= elecCount; i++) {
            OrgNode node = selectNode(i, workshops, processNodes, utilNode);
            createMeter(PREFIX + "M-ELEC-" + String.format("%03d", i), "电表-" + i, elecTypeId, node.getId());
            created++;
        }

        // water meters
        for (int i = 1; i <= scale.getWaterMeters(); i++) {
            OrgNode node = selectNode(i, workshops, processNodes, utilNode);
            createMeter(PREFIX + "M-WATER-" + String.format("%03d", i), "水表-" + i, waterTypeId, node.getId());
            created++;
        }

        // steam meters
        for (int i = 1; i <= scale.getSteamMeters(); i++) {
            OrgNode node = workshops.isEmpty() ? utilNode : workshops.get(i % workshops.size());
            createMeter(PREFIX + "M-STEAM-" + String.format("%03d", i), "蒸汽表-" + i, steamTypeId, node.getId());
            created++;
        }

        log.info("Seeded {} meters", created);
    }

    private OrgNode selectNode(int idx, List<OrgNode> workshops,
                               List<OrgNode> processes, OrgNode fallback) {
        if (!processes.isEmpty()) return processes.get(idx % processes.size());
        if (!workshops.isEmpty()) return workshops.get(idx % workshops.size());
        return fallback;
    }

    private void createMeter(String code, String name, Long energyTypeId, Long orgNodeId) {
        Meter m = new Meter();
        m.setCode(code);
        m.setName(name);
        m.setEnergyTypeId(energyTypeId);
        m.setOrgNodeId(orgNodeId);
        m.setInfluxMeasurement(MEASUREMENT);
        m.setInfluxTagKey(TAG_KEY);
        m.setInfluxTagValue(code);
        m.setEnabled(true);
        meterRepo.save(m);
    }

    private Long findEnergyTypeId(String code) {
        return jdbc.queryForObject(
            "SELECT id FROM energy_types WHERE code = ?", Long.class, code);
    }
}
