package com.ems.collector.runtime;

import java.time.Instant;

/**
 * 当 channel 连续失败次数达到 {@link ChannelStateRegistry#FAILURE_THRESHOLD} 时由 registry 发布。
 *
 * <p>消费者：{@code com.ems.alarm.service.impl.ChannelAlarmListener}（创建 COMMUNICATION_FAULT 报警）。
 *
 * <p>同一 channel 进入 fault 状态后该事件只会发布一次（直到收到一次成功 → 解除 fault flag）。
 */
public record ChannelFailureEvent(
        Long channelId,
        String protocol,
        String errorMessage,
        int consecutiveFailures,
        Instant occurredAt
) {}
