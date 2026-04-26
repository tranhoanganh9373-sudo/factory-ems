package com.ems.billing.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillPeriodTest {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final Long ACTOR = 42L;
    private static final Long OTHER_ACTOR = 99L;

    private BillPeriod fresh(String ym) {
        BillPeriod p = new BillPeriod();
        p.setYearMonth(ym);
        p.setPeriodStart(OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z));
        p.setPeriodEnd(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, Z));
        return p;
    }

    @Test
    void newly_created_period_is_OPEN() {
        BillPeriod p = fresh("2026-03");
        assertThat(p.getStatus()).isEqualTo(BillPeriodStatus.OPEN);
        assertThat(p.isOpen()).isTrue();
        assertThat(p.isClosed()).isFalse();
        assertThat(p.isLocked()).isFalse();
        assertThat(p.getClosedAt()).isNull();
        assertThat(p.getClosedBy()).isNull();
        assertThat(p.getLockedAt()).isNull();
        assertThat(p.getLockedBy()).isNull();
    }

    @Test
    void close_OPEN_to_CLOSED_records_actor_and_timestamp() {
        BillPeriod p = fresh("2026-03");
        p.close(ACTOR);
        assertThat(p.getStatus()).isEqualTo(BillPeriodStatus.CLOSED);
        assertThat(p.getClosedBy()).isEqualTo(ACTOR);
        assertThat(p.getClosedAt()).isNotNull();
    }

    @Test
    void close_CLOSED_again_overwrites_actor_and_timestamp() throws InterruptedException {
        BillPeriod p = fresh("2026-03");
        p.close(ACTOR);
        OffsetDateTime firstClose = p.getClosedAt();
        Thread.sleep(2);
        p.close(OTHER_ACTOR);
        assertThat(p.getStatus()).isEqualTo(BillPeriodStatus.CLOSED);
        assertThat(p.getClosedBy()).isEqualTo(OTHER_ACTOR);
        assertThat(p.getClosedAt()).isAfter(firstClose);
    }

    @Test
    void close_LOCKED_period_is_rejected() {
        BillPeriod p = fresh("2026-03");
        p.close(ACTOR);
        p.lock(ACTOR);
        assertThatThrownBy(() -> p.close(ACTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCKED");
    }

    @Test
    void lock_CLOSED_to_LOCKED_records_actor_and_timestamp() {
        BillPeriod p = fresh("2026-03");
        p.close(ACTOR);
        p.lock(OTHER_ACTOR);
        assertThat(p.getStatus()).isEqualTo(BillPeriodStatus.LOCKED);
        assertThat(p.isLocked()).isTrue();
        assertThat(p.getLockedBy()).isEqualTo(OTHER_ACTOR);
        assertThat(p.getLockedAt()).isNotNull();
    }

    @Test
    void lock_OPEN_period_is_rejected() {
        BillPeriod p = fresh("2026-03");
        assertThatThrownBy(() -> p.lock(ACTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    void lock_LOCKED_period_is_rejected() {
        BillPeriod p = fresh("2026-03");
        p.close(ACTOR);
        p.lock(ACTOR);
        assertThatThrownBy(() -> p.lock(ACTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    void unlock_LOCKED_to_CLOSED_clears_lock_metadata() {
        BillPeriod p = fresh("2026-03");
        p.close(ACTOR);
        p.lock(ACTOR);
        p.unlock();
        assertThat(p.getStatus()).isEqualTo(BillPeriodStatus.CLOSED);
        assertThat(p.getLockedAt()).isNull();
        assertThat(p.getLockedBy()).isNull();
        assertThat(p.getClosedAt()).isNotNull();   // closedAt 保留，便于审计
        assertThat(p.getClosedBy()).isNotNull();
    }

    @Test
    void unlock_OPEN_period_is_rejected() {
        BillPeriod p = fresh("2026-03");
        assertThatThrownBy(p::unlock)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCKED");
    }

    @Test
    void unlock_CLOSED_period_is_rejected() {
        BillPeriod p = fresh("2026-03");
        p.close(ACTOR);
        assertThatThrownBy(p::unlock)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCKED");
    }

    @Test
    void assertWritable_passes_for_OPEN_and_CLOSED() {
        BillPeriod open = fresh("2026-03");
        open.assertWritable();   // 不抛异常即过

        BillPeriod closed = fresh("2026-04");
        closed.close(ACTOR);
        closed.assertWritable();
    }

    @Test
    void assertWritable_throws_for_LOCKED() {
        BillPeriod p = fresh("2026-03");
        p.close(ACTOR);
        p.lock(ACTOR);
        assertThatThrownBy(p::assertWritable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCKED");
    }

    @Test
    void close_lock_unlock_close_full_cycle() {
        BillPeriod p = fresh("2026-03");
        p.close(ACTOR);
        assertThat(p.isClosed()).isTrue();
        p.lock(ACTOR);
        assertThat(p.isLocked()).isTrue();
        p.unlock();
        assertThat(p.isClosed()).isTrue();
        p.close(OTHER_ACTOR);    // 重新关账期 (CLOSED -> CLOSED)
        assertThat(p.isClosed()).isTrue();
        assertThat(p.getClosedBy()).isEqualTo(OTHER_ACTOR);
    }
}
