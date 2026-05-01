package com.ems.collector.channel;

import com.ems.collector.protocol.VirtualConfig;
import com.ems.collector.protocol.VirtualMode;
import com.ems.collector.protocol.VirtualPoint;
import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.runtime.ConnectionState;
import com.ems.collector.sink.SampleWriter;
import com.ems.collector.transport.ChannelTransportFactory;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.SampleSink;
import com.ems.collector.transport.Transport;
import com.ems.collector.transport.TransportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用 mock 隔离 ChannelRepository / ChannelTransportFactory / SampleWriter；
 * ChannelStateRegistry 用真实实例（fixed clock）便于 Java 25 + Mockito 5.11 兼容
 * （Mockito 在 Java 25 上无法 mock 部分类），通过 snapshot() 反查真实状态变化。
 */
class ChannelServiceTest {

    private final ChannelRepository repo = mock(ChannelRepository.class);
    private final SampleWriter sinkSvc = mock(SampleWriter.class);
    private ChannelStateRegistry registry;
    private RecordingFactory factoryMock;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-30T10:00:00Z"), ZoneOffset.UTC);
        registry = new ChannelStateRegistry(clock, event -> {});
        factoryMock = new RecordingFactory();
    }

    /**
     * 替代 mock(ChannelTransportFactory.class) — Java 25 + Mockito 5.11 不能 mock 该类。
     * 用真实子类，通过 stub queue 提供预构造的 Transport mock。
     */
    static final class RecordingFactory extends ChannelTransportFactory {
        private final java.util.Deque<Transport> queue = new java.util.ArrayDeque<>();
        int createCount = 0;

        RecordingFactory() {
            super(null, null, null);
        }

        void enqueue(Transport... ts) {
            for (Transport t : ts) queue.add(t);
        }

        @Override
        public Transport create(String protocol) {
            createCount++;
            if (queue.isEmpty()) {
                throw new IllegalStateException("no Transport stubbed for " + protocol);
            }
            return queue.poll();
        }
    }

    @Test
    void startsTransportOnCreate() {
        Transport transport = mock(Transport.class);
        factoryMock.enqueue(transport);

        Channel ch = newChannel(42L, "VIRTUAL", true);
        when(repo.save(any(Channel.class))).thenReturn(ch);

        ChannelService svc = new ChannelService(repo, registry, factoryMock, sinkSvc);
        Channel saved = svc.create(ch);

        assertThat(saved.getId()).isEqualTo(42L);
        verify(transport).start(eq(42L), any(VirtualConfig.class), any());
        assertThat(svc.activeTransport(42L)).isPresent();
        // 真实 registry：register 已被调用
        assertThat(registry.snapshot(42L)).isNotNull()
                .extracting("protocol").isEqualTo("VIRTUAL");
    }

    @Test
    void doesNotStartWhenChannelDisabled() {
        Channel ch = newChannel(43L, "VIRTUAL", false);
        when(repo.save(any(Channel.class))).thenReturn(ch);

        ChannelService svc = new ChannelService(repo, registry, factoryMock, sinkSvc);
        svc.create(ch);

        assertThat(factoryMock.createCount).isZero();
        assertThat(registry.snapshot(43L)).isNull();
    }

    @Test
    void deleteStopsActiveTransport() {
        Transport transport = mock(Transport.class);
        factoryMock.enqueue(transport);
        Channel ch = newChannel(44L, "VIRTUAL", true);
        when(repo.save(any(Channel.class))).thenReturn(ch);

        ChannelService svc = new ChannelService(repo, registry, factoryMock, sinkSvc);
        svc.create(ch);

        svc.delete(44L);

        verify(transport).stop();
        assertThat(svc.activeTransport(44L)).isEmpty();
        assertThat(registry.snapshot(44L)).isNull();
    }

    @Test
    void restartStopsOldStartsNewWithoutPersisting() {
        Transport oldT = mock(Transport.class);
        Transport newT = mock(Transport.class);
        factoryMock.enqueue(oldT, newT);

        Channel ch = newChannel(46L, "VIRTUAL", true);
        when(repo.save(any(Channel.class))).thenReturn(ch);
        when(repo.findById(46L)).thenReturn(java.util.Optional.of(ch));

        ChannelService svc = new ChannelService(repo, registry, factoryMock, sinkSvc);
        svc.create(ch);

        svc.restart(46L);

        verify(oldT).stop();
        verify(newT).start(eq(46L), any(VirtualConfig.class), any());
        // 关键：restart 不应调用 repo.save
        verify(repo, org.mockito.Mockito.times(1)).save(any(Channel.class));
    }

    @Test
    void restartDisabledChannelOnlyStops() {
        Transport oldT = mock(Transport.class);
        factoryMock.enqueue(oldT);

        Channel enabled = newChannel(47L, "VIRTUAL", true);
        when(repo.save(any(Channel.class))).thenReturn(enabled);

        ChannelService svc = new ChannelService(repo, registry, factoryMock, sinkSvc);
        svc.create(enabled);

        // 模拟 channel 在 DB 已被 disabled
        Channel disabled = newChannel(47L, "VIRTUAL", false);
        when(repo.findById(47L)).thenReturn(java.util.Optional.of(disabled));

        svc.restart(47L);

        verify(oldT).stop();
        // factory 没再次被调用
        assertThat(factoryMock.createCount).isEqualTo(1);
        assertThat(svc.activeTransport(47L)).isEmpty();
    }

    @Test
    void restartUnknownChannelThrows() {
        when(repo.findById(48L)).thenReturn(java.util.Optional.empty());
        ChannelService svc = new ChannelService(repo, registry, factoryMock, sinkSvc);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.restart(48L))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("48");
    }

    @Test
    void updateStopsOldStartsNew() {
        Transport oldT = mock(Transport.class);
        Transport newT = mock(Transport.class);
        factoryMock.enqueue(oldT, newT);

        Channel ch = newChannel(45L, "VIRTUAL", true);
        when(repo.save(any(Channel.class))).thenReturn(ch);

        ChannelService svc = new ChannelService(repo, registry, factoryMock, sinkSvc);
        svc.create(ch);

        Channel updated = newChannel(45L, "VIRTUAL", true);
        svc.update(45L, updated);

        verify(oldT).stop();
        verify(newT).start(eq(45L), any(VirtualConfig.class), any());
    }

    @Test
    void startAllEnabledMarksFailedChannelAsErrorButContinues() {
        Channel okCh = newChannel(50L, "VIRTUAL", true);
        Channel badCh = newChannel(51L, "VIRTUAL", true);
        when(repo.findByEnabledTrue()).thenReturn(List.of(badCh, okCh));

        Transport okT = mock(Transport.class);
        Transport badT = mock(Transport.class);
        // findByEnabledTrue() 返回顺序：badCh, okCh
        factoryMock.enqueue(badT, okT);
        doThrow(new TransportException("boom"))
                .when(badT).start(eq(51L), any(), any());

        ChannelService svc = new ChannelService(repo, registry, factoryMock, sinkSvc);
        svc.startAllEnabled();

        // bad channel: ERROR + recordFailure
        // setState(ERROR) 在 recordFailure 之后调用，最终状态为 ERROR，错误信息也已记录。
        assertThat(registry.snapshot(51L).connState()).isEqualTo(ConnectionState.ERROR);
        assertThat(registry.snapshot(51L).lastErrorMessage()).contains("boom");
        // ok channel started
        verify(okT).start(eq(50L), any(VirtualConfig.class), any());
    }

    @Test
    void sampleCallbackTriggersWriterAndStateRegistry() {
        Transport transport = mock(Transport.class);
        factoryMock.enqueue(transport);
        Channel ch = newChannel(60L, "VIRTUAL", true);
        when(repo.save(any(Channel.class))).thenReturn(ch);

        ChannelService svc = new ChannelService(repo, registry, factoryMock, sinkSvc);
        svc.create(ch);

        ArgumentCaptor<SampleSink> sinkCaptor = ArgumentCaptor.forClass(SampleSink.class);
        verify(transport).start(eq(60L), any(VirtualConfig.class), sinkCaptor.capture());

        Sample sample = new Sample(60L, "p1",
                Instant.parse("2026-04-30T10:00:00Z"),
                1.5, Quality.GOOD, Map.of());
        sinkCaptor.getValue().accept(sample);

        verify(sinkSvc).write(sample);
        // 真实 registry 验证：CONNECTED + lastSuccessAt 不为 null
        var snap = registry.snapshot(60L);
        assertThat(snap.connState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(snap.successCount24h()).isEqualTo(1L);
    }

    @Test
    void badSampleRecordsFailureNotSuccess() {
        Transport transport = mock(Transport.class);
        factoryMock.enqueue(transport);
        Channel ch = newChannel(61L, "VIRTUAL", true);
        when(repo.save(any(Channel.class))).thenReturn(ch);

        ChannelService svc = new ChannelService(repo, registry, factoryMock, sinkSvc);
        svc.create(ch);

        ArgumentCaptor<SampleSink> sinkCaptor = ArgumentCaptor.forClass(SampleSink.class);
        verify(transport).start(eq(61L), any(VirtualConfig.class), sinkCaptor.capture());

        Sample bad = new Sample(61L, "p1",
                Instant.parse("2026-04-30T10:00:00Z"),
                null, Quality.BAD, Map.of("error", "modbus timeout"));
        sinkCaptor.getValue().accept(bad);

        var snap = registry.snapshot(61L);
        assertThat(snap.connState()).isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(snap.successCount24h()).isZero();
        assertThat(snap.failureCount24h()).isEqualTo(1L);
        assertThat(snap.lastErrorMessage()).contains("modbus timeout");
    }

    @Test
    void uncertainSampleRecordsFailureWithFallbackError() {
        Transport transport = mock(Transport.class);
        factoryMock.enqueue(transport);
        Channel ch = newChannel(62L, "VIRTUAL", true);
        when(repo.save(any(Channel.class))).thenReturn(ch);

        ChannelService svc = new ChannelService(repo, registry, factoryMock, sinkSvc);
        svc.create(ch);

        ArgumentCaptor<SampleSink> sinkCaptor = ArgumentCaptor.forClass(SampleSink.class);
        verify(transport).start(eq(62L), any(VirtualConfig.class), sinkCaptor.capture());

        // 没有 error tag — fallback 用 "quality=UNCERTAIN"
        Sample uncertain = new Sample(62L, "p1",
                Instant.parse("2026-04-30T10:00:00Z"),
                null, Quality.UNCERTAIN, Map.of());
        sinkCaptor.getValue().accept(uncertain);

        var snap = registry.snapshot(62L);
        assertThat(snap.successCount24h()).isZero();
        assertThat(snap.failureCount24h()).isEqualTo(1L);
        assertThat(snap.lastErrorMessage()).isEqualTo("quality=UNCERTAIN");
    }

    private static Channel newChannel(Long id, String protocol, boolean enabled) {
        Channel ch = new Channel();
        ch.setId(id);
        ch.setName("ch-" + id);
        ch.setProtocol(protocol);
        ch.setEnabled(enabled);
        ch.setVirtual("VIRTUAL".equals(protocol));
        ch.setProtocolConfig(new VirtualConfig(
                Duration.ofSeconds(1),
                List.of(new VirtualPoint("v", VirtualMode.CONSTANT,
                        Map.of("value", 1.0), null))));
        return ch;
    }

}
