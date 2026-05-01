package com.ems.collector.transport;

/**
 * Transport 启动 / 运行期错误的统一包装。
 *
 * <p>RuntimeException — 上层 ChannelService 捕获后将 channel 状态置为 ERROR，
 * 不阻塞其他 channel 启动。
 */
public class TransportException extends RuntimeException {

    public TransportException(String msg) {
        super(msg);
    }

    public TransportException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
