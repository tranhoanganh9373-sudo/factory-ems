package com.ems.mockdata.seed;

import com.ems.orgtree.entity.OrgNode;
import com.ems.orgtree.repository.OrgNodeClosureRepository;
import com.ems.orgtree.repository.OrgNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds: 1 factory -> 4 workshops -> 4-6 processes each + 1 common area.
 * All codes prefixed with MOCK- so reset logic can identify them.
 * Uses OrgNodeClosureRepository.insertForRoot / insertForNewLeaf (same as OrgNodeServiceImpl).
 */
@Component
public class OrgTreeSeeder {

    private static final Logger log = LoggerFactory.getLogger(OrgTreeSeeder.class);

    public static final String PREFIX = "MOCK-";

    private final OrgNodeRepository nodes;
    private final OrgNodeClosureRepository closure;

    public OrgTreeSeeder(OrgNodeRepository nodes, OrgNodeClosureRepository closure) {
        this.nodes = nodes;
        this.closure = closure;
    }

    @Transactional
    public void seed() {
        if (nodes.existsByCode(PREFIX + "FACTORY")) {
            log.info("OrgTree already seeded, skipping");
            return;
        }
        log.info("Seeding org tree...");

        OrgNode factory = createNode(null, PREFIX + "FACTORY", "MOCK工厂", "FACTORY", 0);

        String[][] workshops = {
            { PREFIX + "WS-A", "冲压车间",    "WORKSHOP", "1" },
            { PREFIX + "WS-B", "焊接车间",    "WORKSHOP", "2" },
            { PREFIX + "WS-C", "涂装车间",    "WORKSHOP", "3" },
            { PREFIX + "WS-D", "总装车间",    "WORKSHOP", "4" },
        };

        for (String[] ws : workshops) {
            OrgNode workshop = createNode(factory.getId(), ws[0], ws[1], ws[2],
                Integer.parseInt(ws[3]));
            createProcesses(workshop, ws[0]);
        }

        // common area (utility)
        createNode(factory.getId(), PREFIX + "UTIL", "公共动力区", "AREA", 10);

        log.info("OrgTree seeding done");
    }

    private void createProcesses(OrgNode parent, String wsCode) {
        int count = wsCode.endsWith("A") || wsCode.endsWith("C") ? 6 : 4;
        for (int i = 1; i <= count; i++) {
            createNode(parent.getId(),
                wsCode + "-P" + i,
                parent.getName() + "-工序" + i,
                "PROCESS",
                i);
        }
    }

    private OrgNode createNode(Long parentId, String code, String name, String type, int sort) {
        OrgNode n = new OrgNode();
        n.setParentId(parentId);
        n.setCode(code);
        n.setName(name);
        n.setNodeType(type);
        n.setSortOrder(sort);
        nodes.save(n);
        if (parentId == null) {
            closure.insertForRoot(n.getId());
        } else {
            closure.insertForNewLeaf(n.getId(), parentId);
        }
        return n;
    }
}
