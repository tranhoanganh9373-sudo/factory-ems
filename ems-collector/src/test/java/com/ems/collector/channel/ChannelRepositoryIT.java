package com.ems.collector.channel;

import com.ems.collector.protocol.VirtualConfig;
import com.ems.collector.protocol.VirtualMode;
import com.ems.collector.protocol.VirtualPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ChannelRepositoryIT.TestApp.class)
@Transactional
@EnabledIfEnvironmentVariable(
    named = "DOCKER_TC_AVAILABLE",
    matches = "true",
    disabledReason = "Docker testcontainers 与本地 Docker Desktop 不兼容；待上游 testcontainers 升级 docker-java 3.5+ 后启用"
)
class ChannelRepositoryIT {

    @SpringBootApplication
    @EnableJpaRepositories(basePackageClasses = ChannelRepository.class)
    static class TestApp {}

    @Autowired
    ChannelRepository repo;

    @Test
    void persistAndLoad_virtualChannel_preservesJsonbConfig() {
        var ch = newVirtualChannel("virt-1", true);
        var saved = repo.save(ch);
        var loaded = repo.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getProtocolConfig()).isInstanceOf(VirtualConfig.class);
        assertThat(((VirtualConfig) loaded.getProtocolConfig()).points()).hasSize(1);
    }

    @Test
    void findByEnabledTrue_returnsOnlyEnabledChannels() {
        repo.save(newVirtualChannel("ch-on", true));
        repo.save(newVirtualChannel("ch-off", false));
        var enabled = repo.findByEnabledTrue();
        assertThat(enabled).extracting(Channel::getName)
            .contains("ch-on")
            .doesNotContain("ch-off");
    }

    private Channel newVirtualChannel(String name, boolean enabled) {
        var ch = new Channel();
        ch.setName(name);
        ch.setProtocol("VIRTUAL");
        ch.setEnabled(enabled);
        ch.setIsVirtual(true);
        ch.setProtocolConfig(new VirtualConfig(
            Duration.ofSeconds(1),
            List.of(new VirtualPoint("v", VirtualMode.CONSTANT, Map.of("value", 1.0), "kW"))
        ));
        return ch;
    }
}
