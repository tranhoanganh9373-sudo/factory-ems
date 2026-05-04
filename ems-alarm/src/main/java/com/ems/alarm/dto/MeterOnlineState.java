package com.ems.alarm.dto;

/**
 * 测点的实时采集状态。
 *
 * <p>{@link #ONLINE} —— freshness 窗口内（5 分钟）有 GOOD 样本；
 * {@link #OFFLINE} —— 启用但窗口内无 GOOD 样本；
 * {@link #MAINTENANCE} —— 设备处于维护模式，独立于在线/离线，避免维护期被算成离线触发误报。
 *
 * <p>未启用（meter.enabled=false）的测点不会出现在该枚举里——前端直接按 enabled 渲染"未启用"。
 */
public enum MeterOnlineState {
    ONLINE,
    OFFLINE,
    MAINTENANCE
}
