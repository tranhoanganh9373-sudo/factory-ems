package com.ems.alarm.service.impl;

import com.ems.alarm.config.TopologyAlarmProperties;
import com.ems.alarm.repository.TopologyConsistencyHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
@ConditionalOnProperty(name = "ems.alarm.topology.enabled", havingValue = "true")
public class TopologyHistoryTtlCleaner {

    private static final Logger log = LoggerFactory.getLogger(TopologyHistoryTtlCleaner.class);

    private final TopologyConsistencyHistoryRepository repo;
    private final TopologyAlarmProperties props;

    public TopologyHistoryTtlCleaner(TopologyConsistencyHistoryRepository repo, TopologyAlarmProperties props) {
        this.repo = repo;
        this.props = props;
    }

    @Scheduled(cron = "${ems.alarm.topology.ttl-cron:0 15 3 * * *}")
    public void cleanup() {
        OffsetDateTime cutoff = OffsetDateTime.now()
                .minusDays(props.historyRetentionDays());
        int deleted = repo.deleteOlderThan(cutoff);
        log.info("topology_consistency_history TTL cleanup: deleted {} rows older than {}",
                deleted, cutoff);
    }
}
