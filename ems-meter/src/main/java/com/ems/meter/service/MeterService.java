package com.ems.meter.service;

import com.ems.meter.dto.*;

import java.util.List;

public interface MeterService {
    MeterDTO create(CreateMeterReq req);
    MeterDTO update(Long id, UpdateMeterReq req);
    void delete(Long id);
    MeterDTO getById(Long id);
    List<MeterDTO> list(Long orgNodeId, Long energyTypeId, Boolean enabled);
}
