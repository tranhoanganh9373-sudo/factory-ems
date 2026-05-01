package com.ems.collector.runtime;

import java.time.Instant;

/**
 * 当一个 fault 状态的 channel 收到首次成功采样时由 registry 发布。
 *
 * <p>消费者：{@code com.ems.alarm.service.impl.ChannelAlarmListener}（自动解除 COMMUNICATION_FAULT 告警）。
 *
 * <p>从未触发 fault 的 channel 不会发布此事件。
 */
public record ChannelRecoveredEvent(
        Long channelId,
        Instant occurredAt
) {}
