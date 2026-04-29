package com.ems.alarm.service;

public interface AlarmDetector {
    /** 扫描所有受监控设备并触发/恢复告警。catch 单设备异常，不抛出。 */
    void scan();
}
