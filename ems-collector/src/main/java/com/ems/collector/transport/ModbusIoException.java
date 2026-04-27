package com.ems.collector.transport;

/**
 * Modbus 传输层错误的统一包装。把 j2mod 的 {@code com.ghgande.j2mod.modbus.ModbusException} +
 * 其他 IO 异常包成 checked exception，让上层 {@link com.ems.collector.poller.DevicePoller}
 * 统一处理重试 / 状态切换。
 *
 * <p>分 3 种语义（通过子类型区分会更精细，MVP 只用 message + cause + isTransient 标志）：
 * <ul>
 *   <li>transient — 网络抖动、超时；可重试</li>
 *   <li>protocol  — 非法地址、非法功能码；不可重试（配置错）</li>
 *   <li>fatal     — 连接彻底无法建立；进入 UNREACHABLE</li>
 * </ul>
 */
public class ModbusIoException extends Exception {

    private final boolean transientError;

    public ModbusIoException(String message, Throwable cause, boolean transientError) {
        super(message, cause);
        this.transientError = transientError;
    }

    public ModbusIoException(String message, boolean transientError) {
        super(message);
        this.transientError = transientError;
    }

    public boolean isTransient() {
        return transientError;
    }
}
