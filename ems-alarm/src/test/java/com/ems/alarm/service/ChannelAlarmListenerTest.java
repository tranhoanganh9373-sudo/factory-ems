package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.observability.AlarmMetrics;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.impl.ChannelAlarmListener;
import com.ems.collector.runtime.ChannelFailureEvent;
import com.ems.collector.runtime.ChannelRecoveredEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.FluentQuery;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 单元测试 {@link ChannelAlarmListener}。
 *
 * <p>JDK 25 + Mockito 5.x byte-buddy 不能 mock 当前项目里大多数 Spring 类（含
 * {@link AlarmStateMachine}），因此本测试一律用真实实例 + 手写 Stub。
 */
class ChannelAlarmListenerTest {

    private final Clock fixed = Clock.fixed(Instant.parse("2026-04-30T10:00:00Z"), ZoneOffset.UTC);
    private final StubAlarmRepository alarms = new StubAlarmRepository();
    private final RecordingDispatcher dispatcher = new RecordingDispatcher();
    private final AlarmStateMachine sm = new AlarmStateMachine();

    private final ChannelAlarmListener listener = new ChannelAlarmListener(
            alarms, sm, dispatcher, AlarmMetrics.NOOP, fixed);

    private ChannelFailureEvent failure(Long id) {
        return new ChannelFailureEvent(id, "MODBUS_TCP", "connection refused", 5, fixed.instant());
    }

    private ChannelRecoveredEvent recovered(Long id) {
        return new ChannelRecoveredEvent(id, fixed.instant());
    }

    @Test
    void onChannelFailure_noActive_createsAlarmWithChannelScope() {
        listener.onChannelFailure(failure(7L));

        assertThat(alarms.saved).hasSize(1);
        Alarm a = alarms.saved.get(0);
        assertThat(a.getDeviceType()).isEqualTo("CHANNEL");
        assertThat(a.getDeviceId()).isEqualTo(7L);
        assertThat(a.getAlarmType()).isEqualTo(AlarmType.COMMUNICATION_FAULT);
        assertThat(a.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(a.getSeverity()).isEqualTo("WARNING");
        assertThat(a.getTriggeredAt()).isEqualTo(OffsetDateTime.ofInstant(fixed.instant(), ZoneOffset.UTC));
        assertThat(a.getDetail()).containsEntry("protocol", "MODBUS_TCP");
        assertThat(a.getDetail()).containsEntry("errorMessage", "connection refused");
        assertThat(a.getDetail()).containsEntry("consecutiveFailures", 5);

        assertThat(dispatcher.dispatched).containsExactly(a);
    }

    @Test
    void onChannelFailure_existingActive_isIdempotent() {
        Alarm existing = new Alarm();
        existing.setDeviceId(7L);
        existing.setAlarmType(AlarmType.COMMUNICATION_FAULT);
        existing.setStatus(AlarmStatus.ACTIVE);
        alarms.activeByKey.put(key(7L, AlarmType.COMMUNICATION_FAULT), existing);

        listener.onChannelFailure(failure(7L));

        assertThat(alarms.saved).isEmpty();
        assertThat(dispatcher.dispatched).isEmpty();
    }

    @Test
    void onChannelRecovered_withActive_resolvesAlarm() {
        Alarm active = new Alarm();
        active.setDeviceType("CHANNEL");
        active.setDeviceId(8L);
        active.setStatus(AlarmStatus.ACTIVE);
        active.setAlarmType(AlarmType.COMMUNICATION_FAULT);
        active.setTriggeredAt(OffsetDateTime.ofInstant(fixed.instant(), ZoneOffset.UTC));
        alarms.activeByKey.put(key(8L, AlarmType.COMMUNICATION_FAULT), active);

        listener.onChannelRecovered(recovered(8L));

        assertThat(active.getStatus()).isEqualTo(AlarmStatus.RESOLVED);
        assertThat(active.getResolvedReason()).isEqualTo(ResolvedReason.AUTO);
        assertThat(active.getResolvedAt()).isNotNull();
        assertThat(alarms.saved).containsExactly(active);
        assertThat(dispatcher.resolvedDispatched).containsExactly(active);
    }

    @Test
    void onChannelRecovered_noActive_isNoop() {
        listener.onChannelRecovered(recovered(99L));

        assertThat(alarms.saved).isEmpty();
        assertThat(dispatcher.resolvedDispatched).isEmpty();
    }

    @Test
    void onChannelFailure_virtualProtocol_skipsAlarm() {
        ChannelFailureEvent ev = new ChannelFailureEvent(
                42L, "VIRTUAL", "simulated fail", 5, fixed.instant());

        listener.onChannelFailure(ev);

        assertThat(alarms.saved).isEmpty();
        assertThat(dispatcher.dispatched).isEmpty();
    }

    @Test
    void onChannelFailure_repositoryThrows_doesNotPropagate() {
        alarms.findActiveError = new RuntimeException("db down");

        assertThatCode(() -> listener.onChannelFailure(failure(1L))).doesNotThrowAnyException();

        assertThat(alarms.saved).isEmpty();
        assertThat(dispatcher.dispatched).isEmpty();
    }

    @Test
    void onChannelRecovered_repositoryThrows_doesNotPropagate() {
        alarms.findActiveError = new RuntimeException("db down");

        assertThatCode(() -> listener.onChannelRecovered(recovered(1L))).doesNotThrowAnyException();

        assertThat(alarms.saved).isEmpty();
        assertThat(dispatcher.resolvedDispatched).isEmpty();
    }

    private static String key(Long deviceId, AlarmType type) {
        return deviceId + "/" + type.name();
    }

    /** 仅实现本测试用到的 {@link AlarmRepository} 方法；其它走默认 UnsupportedOperation。 */
    private static class StubAlarmRepository implements AlarmRepository {
        final java.util.Map<String, Alarm> activeByKey = new java.util.HashMap<>();
        final List<Alarm> saved = new ArrayList<>();
        RuntimeException findActiveError;

        @Override
        public Optional<Alarm> findActive(Long deviceId, AlarmType type) {
            if (findActiveError != null) throw findActiveError;
            return Optional.ofNullable(activeByKey.get(key(deviceId, type)));
        }

        @Override public long countByStatus(AlarmStatus status) { return 0; }
        @Override public long countActiveByType(AlarmType type) { return 0; }
        @Override public List<Alarm> findTop10ByOrderByTriggeredAtDesc() { return List.of(); }

        @Override public <S extends Alarm> S save(S entity) { saved.add(entity); return entity; }

        // ── 以下为 JpaRepository / JpaSpecificationExecutor 必须实现但本测试不调用 ───
        @Override public void flush() {}
        @Override public <S extends Alarm> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends Alarm> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<Alarm> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> longs) {}
        @Override public void deleteAllInBatch() {}
        @Override public Alarm getOne(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public Alarm getById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public Alarm getReferenceById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public <S extends Alarm> List<S> findAll(Example<S> example) { return List.of(); }
        @Override public <S extends Alarm> List<S> findAll(Example<S> example, Sort sort) { return List.of(); }
        @Override public <S extends Alarm> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public Optional<Alarm> findById(Long aLong) { return Optional.empty(); }
        @Override public boolean existsById(Long aLong) { return false; }
        @Override public List<Alarm> findAll() { return List.of(); }
        @Override public List<Alarm> findAllById(Iterable<Long> longs) { return List.of(); }
        @Override public long count() { return 0; }
        @Override public void deleteById(Long aLong) {}
        @Override public void delete(Alarm entity) {}
        @Override public void deleteAllById(Iterable<? extends Long> longs) {}
        @Override public void deleteAll(Iterable<? extends Alarm> entities) {}
        @Override public void deleteAll() {}
        @Override public List<Alarm> findAll(Sort sort) { return List.of(); }
        @Override public Page<Alarm> findAll(Pageable pageable) { return Page.empty(); }
        @Override public <S extends Alarm> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override public <S extends Alarm> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override public <S extends Alarm> long count(Example<S> example) { return 0; }
        @Override public <S extends Alarm> boolean exists(Example<S> example) { return false; }
        @Override public <S extends Alarm, R> R findBy(Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
        @Override public Optional<Alarm> findOne(Specification<Alarm> spec) { return Optional.empty(); }
        @Override public List<Alarm> findAll(Specification<Alarm> spec) { return Collections.emptyList(); }
        @Override public Page<Alarm> findAll(Specification<Alarm> spec, Pageable pageable) { return Page.empty(); }
        @Override public List<Alarm> findAll(Specification<Alarm> spec, Sort sort) { return Collections.emptyList(); }
        @Override public long count(Specification<Alarm> spec) { return 0; }
        @Override public boolean exists(Specification<Alarm> spec) { return false; }
        @Override public long delete(Specification<Alarm> spec) { return 0; }
        @Override public <S extends Alarm, R> R findBy(Specification<Alarm> spec, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }

    private static final class RecordingDispatcher implements AlarmDispatcher {
        final List<Alarm> dispatched = new ArrayList<>();
        final List<Alarm> resolvedDispatched = new ArrayList<>();
        @Override public void dispatch(Alarm alarm) { dispatched.add(alarm); }
        @Override public void dispatchResolved(Alarm alarm) { resolvedDispatched.add(alarm); }
    }
}
