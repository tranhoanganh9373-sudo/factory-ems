package com.ems.alarm.service.impl;

import com.ems.alarm.config.TopologyAlarmProperties;
import com.ems.alarm.dto.TopologyTransition;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.entity.TopologyConsistencyHistory;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.repository.TopologyConsistencyHistoryRepository;
import com.ems.alarm.service.AlarmDispatcher;
import com.ems.alarm.service.TopologyConsistencyAlarmService;
import com.ems.dashboard.dto.RangeQuery;
import com.ems.dashboard.dto.RangeType;
import com.ems.dashboard.dto.TopologyConsistencyDTO;
import com.ems.dashboard.service.DashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TopologyConsistencyAlarmServiceImpl implements TopologyConsistencyAlarmService {

    private static final Logger log = LoggerFactory.getLogger(TopologyConsistencyAlarmServiceImpl.class);

    private final AlarmRepository alarmRepository;
    private final TopologyConsistencyHistoryRepository historyRepository;
    private final AlarmDispatcher dispatcher;
    private final DashboardService dashboardService;
    private final TopologyAlarmProperties props;

    public TopologyConsistencyAlarmServiceImpl(AlarmRepository alarmRepository,
                                               TopologyConsistencyHistoryRepository historyRepository,
                                               AlarmDispatcher dispatcher,
                                               DashboardService dashboardService,
                                               TopologyAlarmProperties props) {
        this.alarmRepository = alarmRepository;
        this.historyRepository = historyRepository;
        this.dispatcher = dispatcher;
        this.dashboardService = dashboardService;
        this.props = props;
    }

    @Override
    public List<TopologyTransition> classify(List<TopologyConsistencyDTO> rows) {
        double enter = props.enterThreshold();
        double exit = props.exitThreshold();
        List<TopologyTransition> out = new ArrayList<>(rows.size());

        for (TopologyConsistencyDTO row : rows) {
            Optional<Alarm> active = alarmRepository.findActive(
                    row.parentMeterId(), AlarmType.TOPOLOGY_NEGATIVE_RESIDUAL);

            Double ratio = row.residualRatio();
            if (ratio == null) {
                out.add(new TopologyTransition.NoOp(row));
                continue;
            }

            if (active.isPresent()) {
                if (ratio > exit) {
                    out.add(new TopologyTransition.Exit(row, active.get().getId()));
                } else {
                    out.add(new TopologyTransition.Sustain(row, active.get().getId()));
                }
            } else {
                if (ratio <= enter) {
                    out.add(new TopologyTransition.Enter(row));
                } else {
                    out.add(new TopologyTransition.NoOp(row));
                }
            }
        }
        return out;
    }

    @Override
    @Transactional
    public void apply(List<TopologyTransition> transitions) {
        OffsetDateTime now = OffsetDateTime.now();
        for (TopologyTransition t : transitions) {
            historyRepository.save(toHistory(t.row(), now));
            switch (t) {
                case TopologyTransition.NoOp noop -> { /* nothing more */ }
                case TopologyTransition.Enter enter -> handleEnter(enter, now);
                case TopologyTransition.Sustain sustain -> handleSustain(sustain, now);
                case TopologyTransition.Exit exit -> handleExit(exit, now);
            }
        }
    }

    @Override
    public void runOnce() {
        var rows = dashboardService.topologyConsistency(
                new RangeQuery(RangeType.TODAY, null, null, null, null));
        log.debug("topology consistency check: {} rows", rows.size());
        apply(classify(rows));
    }

    private void handleEnter(TopologyTransition.Enter enter, OffsetDateTime now) {
        TopologyConsistencyDTO r = enter.row();
        Alarm a = new Alarm();
        a.setDeviceId(r.parentMeterId());
        a.setDeviceType("METER");
        a.setAlarmType(AlarmType.TOPOLOGY_NEGATIVE_RESIDUAL);
        a.setSeverity("MEDIUM");
        a.setStatus(AlarmStatus.ACTIVE);
        a.setTriggeredAt(now);
        a.setLastSeenAt(now);
        a.setDetail(buildDetail(r, now));
        alarmRepository.save(a);
        dispatcher.dispatch(a);
        log.info("[topology-alarm] ENTER parent={} ratio={}", r.parentMeterId(), r.residualRatio());
    }

    private void handleSustain(TopologyTransition.Sustain sustain, OffsetDateTime now) {
        Alarm a = alarmRepository.findById(sustain.alarmId()).orElseThrow();
        a.setLastSeenAt(now);
        alarmRepository.save(a);
    }

    private void handleExit(TopologyTransition.Exit exit, OffsetDateTime now) {
        TopologyConsistencyDTO r = exit.row();
        Alarm a = alarmRepository.findById(exit.alarmId()).orElseThrow();
        a.setStatus(AlarmStatus.ACKED);
        a.setAckedAt(now);
        a.setResolvedAt(now);
        a.setResolvedReason(ResolvedReason.AUTO);
        Map<String, Object> detail = new HashMap<>(a.getDetail() != null ? a.getDetail() : Map.of());
        detail.put("auto_ack_reason", "residual_recovered");
        detail.put("auto_ack_at", now.toString());
        detail.put("recovery_ratio", r.residualRatio());
        a.setDetail(detail);
        alarmRepository.save(a);
        dispatcher.dispatchResolved(a);
        log.info("[topology-alarm] EXIT parent={} ratio={}", r.parentMeterId(), r.residualRatio());
    }

    private TopologyConsistencyHistory toHistory(TopologyConsistencyDTO r, OffsetDateTime now) {
        return new TopologyConsistencyHistory(
                r.parentMeterId(),
                r.energyType(),
                BigDecimal.valueOf(r.parentReading()),
                BigDecimal.valueOf(r.childrenSum()),
                r.childrenCount(),
                BigDecimal.valueOf(r.residual()),
                r.residualRatio() == null ? null : BigDecimal.valueOf(r.residualRatio()),
                r.severity(),
                now);
    }

    private Map<String, Object> buildDetail(TopologyConsistencyDTO r, OffsetDateTime now) {
        Map<String, Object> d = new HashMap<>();
        d.put("parent_code", r.parentCode());
        d.put("parent_name", r.parentName());
        d.put("energy_type", r.energyType());
        d.put("residual_ratio", r.residualRatio());
        d.put("children_count", r.childrenCount());
        d.put("trigger_threshold", props.enterThreshold());
        d.put("exit_threshold", props.exitThreshold());
        d.put("first_seen_at", now.toString());
        return d;
    }
}
