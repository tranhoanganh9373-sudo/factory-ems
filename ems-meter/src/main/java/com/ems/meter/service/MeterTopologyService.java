package com.ems.meter.service;

import com.ems.meter.dto.BindParentMeterReq;
import com.ems.meter.dto.MeterTopologyEdgeDTO;

import java.util.List;

public interface MeterTopologyService {
    void bind(Long childMeterId, BindParentMeterReq req);
    void unbind(Long childMeterId);
    List<MeterTopologyEdgeDTO> listAll();
}
