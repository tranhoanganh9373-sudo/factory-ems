package com.ems.collector.diagnostics;

import com.ems.collector.channel.Channel;
import com.ems.collector.channel.ChannelRepository;
import com.ems.collector.channel.ChannelService;
import com.ems.collector.protocol.VirtualConfig;
import com.ems.collector.protocol.VirtualMode;
import com.ems.collector.protocol.VirtualPoint;
import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.sink.DiagnosticRingBuffer;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.TestResult;
import com.ems.collector.transport.Transport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Java 25 + Mockito 5.x 下 ChannelStateRegistry / Channel / ChannelConfig 都不能 mock。
 * 用真实对象 + mock interface（ChannelRepository / Transport）+ mock service（concrete class
 * 仍可 mock，但 ChannelService 在 Java 25 上也不可靠 → 改用最简单的 stub）。
 */
@DisplayName("ChannelDiagnosticsService")
class ChannelDiagnosticsServiceTest {

    private ChannelStateRegistry registry;
    private RecordingChannelService channelService;
    private ChannelRepository repo;
    private DiagnosticRingBuffer ring;
    private ChannelDiagnosticsService svc;

    private static Channel channel(Long id) {
        var ch = new Channel();
        ch.setId(id);
        ch.setName("ch-" + id);
        ch.setProtocol("VIRTUAL");
        ch.setProtocolConfig(new VirtualConfig(
                Duration.ofSeconds(1),
                List.of(new VirtualPoint("p", VirtualMode.CONSTANT, Map.of("value", 1.0), "kW"))));
        return ch;
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-30T10:00:00Z"), ZoneOffset.UTC);
        registry = new ChannelStateRegistry(clock, event -> {});
        channelService = new RecordingChannelService();
        repo = mock(ChannelRepository.class);
        ring = new DiagnosticRingBuffer();
        svc = new ChannelDiagnosticsService(registry, channelService, repo, ring);
    }

    @Test
    @DisplayName("test 无 active transport 返回 not active")
    void test_noActiveTransport_returnsFail() {
        var ch = channel(1L);
        when(repo.findById(1L)).thenReturn(Optional.of(ch));
        channelService.activeTransport = Optional.empty();

        var result = svc.test(1L);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("not active");
    }

    @Test
    @DisplayName("test 有 active transport 委托 testConnection")
    void test_activeTransport_delegatesToTransport() {
        var ch = channel(1L);
        var transport = mock(Transport.class);
        when(repo.findById(1L)).thenReturn(Optional.of(ch));
        when(transport.testConnection(ch.getProtocolConfig())).thenReturn(TestResult.ok(42L));
        channelService.activeTransport = Optional.of(transport);

        var result = svc.test(1L);

        assertThat(result.success()).isTrue();
        assertThat(result.latencyMs()).isEqualTo(42L);
        verify(transport).testConnection(ch.getProtocolConfig());
    }

    @Test
    @DisplayName("test channel 不存在抛 NoSuchElementException")
    void test_channelNotFound_throws() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.test(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("reconnect 委托 channelService.restart（不调 update，避免污染 updatedAt）")
    void reconnect_delegatesToChannelServiceRestart() {
        svc.reconnect(1L);
        assertThat(channelService.lastRestartId).isEqualTo(1L);
        assertThat(channelService.lastUpdateId).isNull();
    }

    @Test
    @DisplayName("snapshotAll 委托 registry")
    void snapshotAll_delegatesToRegistry() {
        registry.register(7L, "VIRTUAL");
        var all = svc.snapshotAll();
        assertThat(all).extracting("channelId").containsExactly(7L);
    }

    @Test
    @DisplayName("getRecentSamples 不存在 channel 返回空列表")
    void getRecentSamples_unknownChannel_returnsEmpty() {
        var result = svc.getRecentSamples(999L, 20);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getRecentSamples 映射 Sample → SampleDTO（最新在前）")
    void getRecentSamples_mapsAndOrdersNewestFirst() {
        var t1 = Instant.parse("2026-04-30T10:00:00Z");
        var t2 = Instant.parse("2026-04-30T10:00:01Z");
        ring.record(new Sample(1L, "p1", t1, 1.5, Quality.GOOD, Map.of("topic", "a")));
        ring.record(new Sample(1L, "p2", t2, 2.5, Quality.UNCERTAIN, Map.of("topic", "b")));

        var result = svc.getRecentSamples(1L, 20);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).pointKey()).isEqualTo("p2");
        assertThat(result.get(0).timestamp()).isEqualTo("2026-04-30T10:00:01Z");
        assertThat(result.get(0).value()).isEqualTo(2.5);
        assertThat(result.get(0).quality()).isEqualTo("UNCERTAIN");
        assertThat(result.get(0).tags()).containsEntry("topic", "b");
        assertThat(result.get(1).pointKey()).isEqualTo("p1");
        assertThat(result.get(1).quality()).isEqualTo("GOOD");
    }

    @Test
    @DisplayName("getRecentSamples limit<=0 → 默认 20")
    void getRecentSamples_nonPositiveLimit_clampsTo20() {
        for (int i = 0; i < 30; i++) {
            ring.record(new Sample(1L, "p" + i, Instant.parse("2026-04-30T10:00:00Z"),
                (double) i, Quality.GOOD, Map.of()));
        }

        var result = svc.getRecentSamples(1L, 0);

        assertThat(result).hasSize(20);
    }

    @Test
    @DisplayName("getRecentSamples limit>100 → 截断到 100")
    void getRecentSamples_limitOver100_clampsTo100() {
        for (int i = 0; i < 100; i++) {
            ring.record(new Sample(1L, "p" + i, Instant.parse("2026-04-30T10:00:00Z"),
                (double) i, Quality.GOOD, Map.of()));
        }

        var result = svc.getRecentSamples(1L, 9999);

        assertThat(result).hasSize(100);
    }

    @Test
    @DisplayName("getRecentSamples 尊重显式 limit")
    void getRecentSamples_explicitLimit_respected() {
        for (int i = 0; i < 10; i++) {
            ring.record(new Sample(1L, "p" + i, Instant.parse("2026-04-30T10:00:00Z"),
                (double) i, Quality.GOOD, Map.of()));
        }

        var result = svc.getRecentSamples(1L, 5);

        assertThat(result).hasSize(5);
    }

    /** Java 25 下 mock(ChannelService) 失败，用真子类 stub。 */
    static final class RecordingChannelService extends ChannelService {
        Optional<Transport> activeTransport = Optional.empty();
        Long lastUpdateId;
        Channel lastUpdateChannel;
        Long lastRestartId;

        RecordingChannelService() {
            super(null, null, null, null);
        }

        @Override
        public Optional<Transport> activeTransport(Long id) {
            return activeTransport;
        }

        @Override
        public Channel update(Long id, Channel updated) {
            this.lastUpdateId = id;
            this.lastUpdateChannel = updated;
            return updated;
        }

        @Override
        public void restart(Long id) {
            this.lastRestartId = id;
        }
    }
}
