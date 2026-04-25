package com.ems.production.service;

import com.ems.production.dto.CreateShiftReq;
import com.ems.production.dto.ShiftDTO;
import com.ems.production.dto.UpdateShiftReq;

import java.util.List;

public interface ShiftService {

    List<ShiftDTO> list(boolean enabledOnly);

    ShiftDTO getById(Long id);

    ShiftDTO create(CreateShiftReq req);

    ShiftDTO update(Long id, UpdateShiftReq req);

    void delete(Long id);
}
