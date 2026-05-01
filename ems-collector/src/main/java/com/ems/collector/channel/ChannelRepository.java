package com.ems.collector.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

    List<Channel> findByEnabledTrue();

    Optional<Channel> findByName(String name);
}
