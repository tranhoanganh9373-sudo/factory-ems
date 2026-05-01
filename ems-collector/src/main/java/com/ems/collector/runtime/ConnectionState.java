package com.ems.collector.runtime;

/** Channel 当前连接状态。CONNECTING 是初始 / 重连中；ERROR 表示启动失败不会自动重试。 */
public enum ConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}
