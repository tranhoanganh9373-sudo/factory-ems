package com.ems.meter.service.impl;

import com.ems.meter.dto.EnergyTypeDTO;
import com.ems.meter.entity.EnergyType;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.service.EnergyTypeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EnergyTypeServiceImpl implements EnergyTypeService {

    private final EnergyTypeRepository repo;

    public EnergyTypeServiceImpl(EnergyTypeRepository repo) { this.repo = repo; }

    @Override
    @Transactional(readOnly = true)
    public List<EnergyTypeDTO> list() {
        return repo.findAllByOrderBySortOrderAscIdAsc().stream().map(this::toDTO).toList();
    }

    private EnergyTypeDTO toDTO(EnergyType e) {
        return new EnergyTypeDTO(e.getId(), e.getCode(), e.getName(), e.getUnit(), e.getSortOrder());
    }
}
