package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.observability.AlarmMetrics;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.impl.CertificatePendingListener;
import com.ems.collector.runtime.ChannelCertificateApprovedEvent;
import com.ems.collector.runtime.ChannelCertificatePendingEvent;
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
 * 单元测试 {@link CertificatePendingListener}。
 * 使用手写 stub（与 ChannelAlarmListenerTest 相同模式）。
 */
class CertificatePendingListenerTest {

    private final Clock fixed = Clock.fixed(Instant.parse("2026-04-30T10:00:00Z"), ZoneOffset.UTC);
    private final StubAlarmRepository alarms = new StubAlarmRepository();
    private final RecordingDispatcher dispatcher = new RecordingDispatcher();
    private final AlarmStateMachine sm = new AlarmStateMachine();

    private final CertificatePendingListener listener = new CertificatePendingListener(
            alarms, sm, dispatcher, AlarmMetrics.NOOP, fixed);

    private ChannelCertificatePendingEvent pendingEvent(Long channelId) {
        return new ChannelCertificatePendingEvent(
                channelId,
                "opc.tcp://plc.example.com:4840",
                "aabbccdd",
                "CN=PLC-Server",
                fixed.instant());
    }

    private ChannelCertificateApprovedEvent approvedEvent(Long channelId) {
        return new ChannelCertificateApprovedEvent(channelId, "aabbccdd", "admin", fixed.instant());
    }

    @Test
    void onCertificatePending_noActive_createsAlarmWithCorrectFields() {
        listener.onCertificatePending(pendingEvent(10L));

        assertThat(alarms.saved).hasSize(1);
        Alarm a = alarms.saved.get(0);
        assertThat(a.getDeviceType()).isEqualTo("CHANNEL");
        assertThat(a.getDeviceId()).isEqualTo(10L);
        assertThat(a.getAlarmType()).isEqualTo(AlarmType.OPC_UA_CERT_PENDING);
        assertThat(a.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(a.getSeverity()).isEqualTo("WARNING");
        assertThat(a.getTriggeredAt()).isEqualTo(OffsetDateTime.ofInstant(fixed.instant(), ZoneOffset.UTC));
        assertThat(a.getDetail()).containsEntry("thumbprint", "aabbccdd");
        assertThat(a.getDetail()).containsEntry("endpointUrl", "opc.tcp://plc.example.com:4840");
        assertThat(a.getDetail()).containsEntry("subjectDn", "CN=PLC-Server");
        assertThat(dispatcher.dispatched).containsExactly(a);
    }

    @Test
    void onCertificatePending_existingActive_isIdempotent() {
        Alarm existing = new Alarm();
        existing.setDeviceId(10L);
        existing.setAlarmType(AlarmType.OPC_UA_CERT_PENDING);
        existing.setStatus(AlarmStatus.ACTIVE);
        alarms.activeByKey.put(key(10L, AlarmType.OPC_UA_CERT_PENDING), existing);

        listener.onCertificatePending(pendingEvent(10L));

        assertThat(alarms.saved).isEmpty();
        assertThat(dispatcher.dispatched).isEmpty();
    }

    @Test
    void onCertificateApproved_withActive_resolvesAlarm() {
        Alarm active = new Alarm();
        active.setDeviceType("CHANNEL");
        active.setDeviceId(10L);
        active.setStatus(AlarmStatus.ACTIVE);
        active.setAlarmType(AlarmType.OPC_UA_CERT_PENDING);
        active.setTriggeredAt(OffsetDateTime.ofInstant(fixed.instant(), ZoneOffset.UTC));
        alarms.activeByKey.put(key(10L, AlarmType.OPC_UA_CERT_PENDING), active);

        listener.onCertificateApproved(approvedEvent(10L));

        assertThat(active.getStatus()).isEqualTo(AlarmStatus.RESOLVED);
        assertThat(active.getResolvedReason()).isEqualTo(ResolvedReason.AUTO);
        assertThat(active.getResolvedAt()).isNotNull();
        assertThat(alarms.saved).containsExactly(active);
        assertThat(dispatcher.resolvedDispatched).containsExactly(active);
    }

    @Test
    void onCertificateApproved_noActive_isNoop() {
        listener.onCertificateApproved(approvedEvent(99L));

        assertThat(alarms.saved).isEmpty();
        assertThat(dispatcher.resolvedDispatched).isEmpty();
    }

    @Test
    void onCertificatePending_repositoryThrows_doesNotPropagate() {
        alarms.findActiveError = new RuntimeException("db down");

        assertThatCode(() -> listener.onCertificatePending(pendingEvent(1L))).doesNotThrowAnyException();

        assertThat(alarms.saved).isEmpty();
        assertThat(dispatcher.dispatched).isEmpty();
    }

    @Test
    void onCertificateApproved_repositoryThrows_doesNotPropagate() {
        alarms.findActiveError = new RuntimeException("db down");

        assertThatCode(() -> listener.onCertificateApproved(approvedEvent(1L))).doesNotThrowAnyException();

        assertThat(alarms.saved).isEmpty();
        assertThat(dispatcher.resolvedDispatched).isEmpty();
    }

    private static String key(Long deviceId, AlarmType type) {
        return deviceId + "/" + type.name();
    }

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
