package com.ems.alarm.service;

import com.ems.alarm.dto.TopologyTransition;
import com.ems.dashboard.dto.TopologyConsistencyDTO;

import java.util.List;

public interface TopologyConsistencyAlarmService {

    /** Pure classification: maps each row to a transition. No DB writes, no dispatch. */
    List<TopologyTransition> classify(List<TopologyConsistencyDTO> rows);

    /** Persist all transitions: alarms table + history table + dispatch notifications. */
    void apply(List<TopologyTransition> transitions);

    /** Convenience: classify + apply in one call. Used by the scheduler. */
    void runOnce();
}
