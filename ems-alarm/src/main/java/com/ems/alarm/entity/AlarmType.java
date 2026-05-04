package com.ems.alarm.entity;

public enum AlarmType {
    SILENT_TIMEOUT,
    CONSECUTIVE_FAIL,
    COMMUNICATION_FAULT,
    OPC_UA_CERT_PENDING,
    TOPOLOGY_NEGATIVE_RESIDUAL
}
