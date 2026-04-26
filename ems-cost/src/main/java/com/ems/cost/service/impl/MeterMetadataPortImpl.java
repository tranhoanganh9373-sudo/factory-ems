package com.ems.cost.service.impl;

import com.ems.cost.service.MeterMetadataPort;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import org.springframework.stereotype.Component;

@Component
public class MeterMetadataPortImpl implements MeterMetadataPort {

    private final MeterRepository meterRepository;

    public MeterMetadataPortImpl(MeterRepository meterRepository) {
        this.meterRepository = meterRepository;
    }

    @Override
    public Long energyTypeIdOf(Long meterId) {
        Meter m = meterRepository.findById(meterId)
                .orElseThrow(() -> new IllegalStateException("Meter not found: id=" + meterId));
        return m.getEnergyTypeId();
    }
}
