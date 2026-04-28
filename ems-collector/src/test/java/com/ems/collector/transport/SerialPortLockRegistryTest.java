package com.ems.collector.transport;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SerialPortLockRegistryTest {

    @Test
    void samePort_returnsSameLock() {
        var reg = new SerialPortLockRegistry();
        ReentrantLock l1 = reg.lockFor("/dev/ttyUSB0");
        ReentrantLock l2 = reg.lockFor("/dev/ttyUSB0");
        assertThat(l1).isSameAs(l2);
    }

    @Test
    void differentPorts_returnDifferentLocks() {
        var reg = new SerialPortLockRegistry();
        ReentrantLock a = reg.lockFor("/dev/ttyUSB0");
        ReentrantLock b = reg.lockFor("/dev/ttyUSB1");
        assertThat(a).isNotSameAs(b);
        assertThat(reg.size()).isEqualTo(2);
    }

    @Test
    void normalize_caseAndWhitespace_areEquivalent() {
        var reg = new SerialPortLockRegistry();
        ReentrantLock a = reg.lockFor("/dev/ttyUSB0");
        ReentrantLock b = reg.lockFor("  /DEV/TTYUSB0  ");
        assertThat(a).isSameAs(b);
        assertThat(reg.size()).isEqualTo(1);
    }

    @Test
    void blankPort_throws() {
        var reg = new SerialPortLockRegistry();
        assertThatThrownBy(() -> reg.lockFor(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reg.lockFor(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serializesConcurrentAccess_onSamePort() throws Exception {
        // 2 个线程同抢同一 port 的 lock；验证临界区一次只有一个执行
        var reg = new SerialPortLockRegistry();
        ReentrantLock lock = reg.lockFor("/dev/ttyUSB0");

        AtomicInteger inside = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);

        Runnable task = () -> {
            ready.countDown();
            try { ready.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int i = 0; i < 1000; i++) {
                lock.lock();
                try {
                    int n = inside.incrementAndGet();
                    if (n > maxObserved.get()) maxObserved.set(n);
                    // tiny critical section
                    Thread.yield();
                    inside.decrementAndGet();
                } finally {
                    lock.unlock();
                }
            }
            done.countDown();
        };
        Thread t1 = new Thread(task, "p1");
        Thread t2 = new Thread(task, "p2");
        t1.start();
        t2.start();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(maxObserved.get()).as("never more than one thread inside critical section")
                .isLessThanOrEqualTo(1);
    }
}
