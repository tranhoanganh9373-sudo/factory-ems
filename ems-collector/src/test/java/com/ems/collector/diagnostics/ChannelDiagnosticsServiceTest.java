package com.ems.collector.diagnostics;

import com.ems.collector.channel.Channel;
import com.ems.collector.channel.ChannelRepository;
import com.ems.collector.channel.ChannelService;
import com.ems.collector.protocol.VirtualConfig;
import com.ems.collector.protocol.VirtualMode;
import com.ems.collector.protocol.VirtualPoint;
import com.ems.collector.runtime.ChannelStateRegistry;
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
        registry = new ChannelStateRegistry(clock);
        channelService = new RecordingChannelService();
        repo = mock(ChannelRepository.class);
        svc = new ChannelDiagnosticsService(registry, channelService, repo);
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
    @DisplayName("reconnect 委托 channelService.update")
    void reconnect_delegatesToChannelService() {
        var ch = channel(1L);
        when(repo.findById(1L)).thenReturn(Optional.of(ch));
        svc.reconnect(1L);
        assertThat(channelService.lastUpdateId).isEqualTo(1L);
        assertThat(channelService.lastUpdateChannel).isSameAs(ch);
    }

    @Test
    @DisplayName("snapshotAll 委托 registry")
    void snapshotAll_delegatesToRegistry() {
        registry.register(7L, "VIRTUAL");
        var all = svc.snapshotAll();
        assertThat(all).extracting("channelId").containsExactly(7L);
    }

    /** Java 25 下 mock(ChannelService) 失败，用真子类 stub。 */
    static final class RecordingChannelService extends ChannelService {
        Optional<Transport> activeTransport = Optional.empty();
        Long lastUpdateId;
        Channel lastUpdateChannel;

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
    }
}
