package com.ems.billing.service.impl;

import com.ems.billing.service.ProductionLookupPort;
import com.ems.production.dto.ProductionSumDTO;
import com.ems.production.repository.ProductionEntryRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProductionLookupPortImpl implements ProductionLookupPort {

    private final ProductionEntryRepository productionRepo;

    public ProductionLookupPortImpl(ProductionEntryRepository productionRepo) {
        this.productionRepo = productionRepo;
    }

    @Override
    public Map<Long, BigDecimal> sumByOrgIds(Collection<Long> orgNodeIds, LocalDate from, LocalDate to) {
        if (orgNodeIds == null || orgNodeIds.isEmpty()) return Map.of();
        List<ProductionSumDTO> rows = productionRepo
                .sumQuantityByOrgNodeIdInAndEntryDateBetween(from, to, orgNodeIds);
        Map<Long, BigDecimal> out = new HashMap<>();
        for (ProductionSumDTO row : rows) {
            out.merge(row.orgNodeId(),
                      row.totalQuantity() == null ? BigDecimal.ZERO : row.totalQuantity(),
                      BigDecimal::add);
        }
        return out;
    }
}
