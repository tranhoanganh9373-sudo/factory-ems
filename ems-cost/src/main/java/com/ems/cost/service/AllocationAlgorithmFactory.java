package com.ems.cost.service;

import com.ems.cost.entity.AllocationAlgorithm;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 把 enum AllocationAlgorithm 分发到具体 AllocationStrategy bean。
 * 新增算法只要新增 @Component implements AllocationStrategy；Factory 自动注册。
 */
@Component
public class AllocationAlgorithmFactory {

    private final Map<AllocationAlgorithm, AllocationStrategy> registry = new EnumMap<>(AllocationAlgorithm.class);

    public AllocationAlgorithmFactory(List<AllocationStrategy> strategies) {
        for (AllocationStrategy s : strategies) {
            AllocationAlgorithm key = s.supports();
            if (registry.put(key, s) != null) {
                throw new IllegalStateException("Duplicate AllocationStrategy for " + key);
            }
        }
    }

    public AllocationStrategy of(AllocationAlgorithm algorithm) {
        AllocationStrategy s = registry.get(algorithm);
        if (s == null) {
            throw new IllegalStateException("No AllocationStrategy registered for " + algorithm);
        }
        return s;
    }
}
