package com.ems.alarm.repository;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public final class AlarmSpecifications {

    private AlarmSpecifications() {}

    public static Specification<Alarm> matching(AlarmStatus status,
                                                Long deviceId,
                                                AlarmType type,
                                                OffsetDateTime from,
                                                OffsetDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (deviceId != null) {
                predicates.add(cb.equal(root.get("deviceId"), deviceId));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("alarmType"), type));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("triggeredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("triggeredAt"), to));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
