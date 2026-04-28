package com.ems.collector.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 同一物理仪表（{@code host:port}）挂多个 unit-id 时共享 ModbusTCPMaster 连接：节省 socket、
 * 避免 OS 端口耗尽。每个 (host, port) 对应一个 PoolEntry：含底层 master + ReentrantLock
 * （ModbusTCPMaster 线程不安全，多 device 通过 lock 串行化）+ 引用计数。
 *
 * <p>策略：
 * <ul>
 *   <li>{@link #acquire(String, int, int)} — get-or-create entry；引用计数 +1</li>
 *   <li>{@link #release(String, int)} — 引用计数 -1；归零时关闭并移除</li>
 *   <li>每个 entry 携带 lock；调用方在 read 前后 lock/unlock</li>
 * </ul>
 *
 * <p>不变量：同 (host, port) 的 master 实例 全局唯一；引用计数严格匹配 acquire/release。
 *
 * <p>Plan 1.5.3 Phase A。Phase B 后续把告警通过 StateTransitionListener 写 audit_logs。
 */
@Component
public class TcpConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(TcpConnectionPool.class);

    private final ConcurrentMap<String, PoolEntry> pool = new ConcurrentHashMap<>();

    /** Get-or-create the pooled master for (host, port). Returns the entry; caller holds
     *  one reference (must {@link #release} when done). */
    public synchronized PoolEntry acquire(String host, int port, int timeoutMs) {
        String key = key(host, port);
        PoolEntry entry = pool.computeIfAbsent(key, k -> {
            log.debug("TcpConnectionPool: creating new entry for {}", k);
            return new PoolEntry(host, port, new TcpModbusMaster(host, port, timeoutMs),
                    new ReentrantLock(/* fair */ true));
        });
        entry.refs++;
        return entry;
    }

    /** Release one reference. Closes + removes when refs drop to 0. */
    public synchronized void release(String host, int port) {
        String key = key(host, port);
        PoolEntry entry = pool.get(key);
        if (entry == null) return;
        entry.refs--;
        if (entry.refs <= 0) {
            entry.master.close();
            pool.remove(key);
            log.debug("TcpConnectionPool: closed and removed entry for {}", key);
        }
    }

    /** Visible for testing. */
    public synchronized int size() { return pool.size(); }

    /** Visible for testing. */
    public synchronized int refs(String host, int port) {
        PoolEntry e = pool.get(key(host, port));
        return e == null ? 0 : e.refs;
    }

    private static String key(String host, int port) {
        return host + ":" + port;
    }

    /** Pool entry exposing the master + the lock callers must use to serialize reads. */
    public static final class PoolEntry {
        public final String host;
        public final int port;
        public final ModbusMaster master;
        public final ReentrantLock lock;
        int refs = 0;

        PoolEntry(String host, int port, ModbusMaster master, ReentrantLock lock) {
            this.host = host;
            this.port = port;
            this.master = master;
            this.lock = lock;
        }
    }
}
