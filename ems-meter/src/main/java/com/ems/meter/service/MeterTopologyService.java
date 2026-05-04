package com.ems.meter.service;

import com.ems.meter.dto.BindParentMeterReq;
import com.ems.meter.dto.MeterTopologyEdgeDTO;

import java.util.List;
import java.util.Map;

public interface MeterTopologyService {
    void bind(Long childMeterId, BindParentMeterReq req);
    void unbind(Long childMeterId);
    List<MeterTopologyEdgeDTO> listAll();

    /**
     * 返回 child_meter_id → parent_meter_id 的全量映射。
     * 用于 dashboard 计算"可见集合的根表"和"叶子表"。表数据量小（< 1k 条），单次加载到内存。
     */
    Map<Long, Long> parentMap();
}
