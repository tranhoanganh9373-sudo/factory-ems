package com.ems.alarm.dto;

import com.ems.dashboard.dto.TopologyConsistencyDTO;

/**
 * Result of evaluating one topology-consistency row against the hysteresis state machine.
 * Concrete subtypes encode the four possible outcomes; the row itself is preserved for
 * downstream persistence (history + alarms).
 */
public sealed interface TopologyTransition {

    TopologyConsistencyDTO row();

    /** No active alarm and ratio still in healthy band. Write history only. */
    record NoOp(TopologyConsistencyDTO row) implements TopologyTransition {}

    /** No active alarm and ratio crossed enter threshold. Create new alarm + dispatch. */
    record Enter(TopologyConsistencyDTO row) implements TopologyTransition {}

    /** Active alarm exists and ratio still below exit threshold. Bump last_seen_at. */
    record Sustain(TopologyConsistencyDTO row, Long alarmId) implements TopologyTransition {}

    /** Active alarm exists and ratio recovered above exit threshold. Auto-ack + dispatch. */
    record Exit(TopologyConsistencyDTO row, Long alarmId) implements TopologyTransition {}
}
