package com.ems.mockdata.seed;

import com.ems.meter.entity.Meter;
import com.ems.meter.entity.MeterTopology;
import com.ems.meter.repository.MeterRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Wires: MAIN -> workshop-level elec meters -> process-level elec meters.
 * Only electric meters get a topology (water/steam are standalone).
 */
@Component
public class MeterTopologySeeder {

    private static final Logger log = LoggerFactory.getLogger(MeterTopologySeeder.class);
    private static final String PREFIX = OrgTreeSeeder.PREFIX;

    private final MeterRepository meterRepo;
    private final MeterTopologyRepository topoRepo;

    public MeterTopologySeeder(MeterRepository meterRepo, MeterTopologyRepository topoRepo) {
        this.meterRepo = meterRepo;
        this.topoRepo = topoRepo;
    }

    @Transactional
    public void seed() {
        if (topoRepo.count() > 0) {
            log.info("MeterTopology already seeded, skipping");
            return;
        }
        log.info("Seeding meter topology...");

        Meter main = meterRepo.findByCode(PREFIX + "M-ELEC-MAIN")
            .orElseThrow(() -> new IllegalStateException("Main meter not found; run MeterSeeder first"));

        List<Meter> elecMeters = meterRepo.findAllByOrderByCodeAsc().stream()
            .filter(m -> m.getCode().startsWith(PREFIX + "M-ELEC-") &&
                         !m.getCode().equals(PREFIX + "M-ELEC-MAIN"))
            .toList();

        if (elecMeters.isEmpty()) {
            log.warn("No electric sub-meters found, skipping topology");
            return;
        }

        // Split into two tiers: first third become "sub-mains" hanging off MAIN,
        // rest hang off sub-mains in round-robin.
        int subMainCount = Math.max(1, elecMeters.size() / 4);
        List<Meter> subMains = elecMeters.subList(0, subMainCount);
        List<Meter> leafMeters = elecMeters.subList(subMainCount, elecMeters.size());

        // tier 1: sub-mains -> MAIN
        for (Meter sm : subMains) {
            link(sm.getId(), main.getId());
        }

        // tier 2: leaf meters -> sub-mains (round-robin)
        for (int i = 0; i < leafMeters.size(); i++) {
            Meter parent = subMains.get(i % subMains.size());
            link(leafMeters.get(i).getId(), parent.getId());
        }

        log.info("Seeded topology: 1 main + {} sub-mains + {} leaf electric meters",
            subMainCount, leafMeters.size());
    }

    private void link(Long childId, Long parentId) {
        MeterTopology t = new MeterTopology();
        t.setChildMeterId(childId);
        t.setParentMeterId(parentId);
        topoRepo.save(t);
    }
}
