package com.ems.collector.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TcpConnectionPoolTest {

    @Test
    void sameHostPort_sharesEntry() {
        var pool = new TcpConnectionPool();
        var a = pool.acquire("192.168.1.10", 502, 1000);
        var b = pool.acquire("192.168.1.10", 502, 1000);
        assertThat(a).isSameAs(b);
        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.refs("192.168.1.10", 502)).isEqualTo(2);
    }

    @Test
    void differentHostsOrPorts_separateEntries() {
        var pool = new TcpConnectionPool();
        pool.acquire("h1", 502, 1000);
        pool.acquire("h1", 503, 1000);
        pool.acquire("h2", 502, 1000);
        assertThat(pool.size()).isEqualTo(3);
    }

    @Test
    void release_decrementsRefs_andRemovesAtZero() {
        var pool = new TcpConnectionPool();
        pool.acquire("h", 502, 1000);
        pool.acquire("h", 502, 1000);
        assertThat(pool.refs("h", 502)).isEqualTo(2);

        pool.release("h", 502);
        assertThat(pool.refs("h", 502)).isEqualTo(1);
        assertThat(pool.size()).isEqualTo(1);

        pool.release("h", 502);
        assertThat(pool.refs("h", 502)).isZero();
        assertThat(pool.size()).isZero();
    }

    @Test
    void releaseUnknown_isNoop() {
        var pool = new TcpConnectionPool();
        pool.release("nonexistent", 502);  // does not throw
        assertThat(pool.size()).isZero();
    }

    @Test
    void poolEntry_carriesMasterAndLock() {
        var pool = new TcpConnectionPool();
        var e = pool.acquire("h", 502, 1000);
        assertThat(e.master).isNotNull();
        assertThat(e.lock).isNotNull();
        assertThat(e.host).isEqualTo("h");
        assertThat(e.port).isEqualTo(502);
        pool.release("h", 502);
    }
}
