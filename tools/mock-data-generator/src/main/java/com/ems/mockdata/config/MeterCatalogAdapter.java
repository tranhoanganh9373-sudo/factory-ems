package com.ems.mockdata.config;

import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import com.ems.timeseries.rollup.MeterCatalogPort;
import com.ems.timeseries.rollup.RollupComputeService.MeterCtx;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class MeterCatalogAdapter implements MeterCatalogPort {

    private final MeterRepository meterRepo;

    public MeterCatalogAdapter(MeterRepository meterRepo) { this.meterRepo = meterRepo; }

    @Override
    public List<MeterCtx> findAllEnabled() {
        return meterRepo.findAll().stream()
            .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
            .map(MeterCatalogAdapter::toCtx)
            .toList();
    }

    @Override
    public Optional<MeterCtx> findById(Long meterId) {
        return meterRepo.findById(meterId).map(MeterCatalogAdapter::toCtx);
    }

    private static MeterCtx toCtx(Meter m) {
        return new MeterCtx(m.getId(), m.getOrgNodeId(), m.getInfluxTagValue());
    }
}
