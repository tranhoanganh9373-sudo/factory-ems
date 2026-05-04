package com.ems.alarm.repository;

import com.ems.alarm.entity.TopologyConsistencyHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Repository
public interface TopologyConsistencyHistoryRepository
        extends JpaRepository<TopologyConsistencyHistory, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM TopologyConsistencyHistory h WHERE h.sampledAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
