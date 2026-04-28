package com.ems.collector.transport;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 同一物理串口（{@code /dev/ttyUSB0}、{@code COM3}）上挂多个 unit-id 时，必须串行 read：
 * 串口物理上是单工总线，并发请求会乱。本注册表按 port 路径键控 {@link ReentrantLock}，
 * 让 {@link com.ems.collector.poller.DevicePoller#pollOnce()} 在 RTU 设备上加锁。
 *
 * <p>键标准化：trim + lowercase（避免 {@code /DEV/ttyUSB0} 与 {@code /dev/ttyusb0}
 * 在某些 OS 上当作两个锁但物理上是同一串口）。
 *
 * <p>线程安全：{@link ConcurrentHashMap#computeIfAbsent} 保证同一 port 只产一个 lock。
 *
 * <p>不需要 close —— ReentrantLock 没资源，registry 是 singleton bean，进程级生命周期。
 */
@Component
public class SerialPortLockRegistry {

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Port 路径标准化为唯一键。空 / null 返回原值（应当被验证器拦下来）。 */
    public static String normalize(String port) {
        if (port == null) return null;
        return port.trim().toLowerCase();
    }

    /** Get-or-create 这个 port 上的互斥锁。 */
    public ReentrantLock lockFor(String port) {
        String key = normalize(port);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("serial port path cannot be blank");
        }
        return locks.computeIfAbsent(key, k -> new ReentrantLock(/* fair = */ true));
    }

    /** Visible for testing. */
    int size() { return locks.size(); }
}
