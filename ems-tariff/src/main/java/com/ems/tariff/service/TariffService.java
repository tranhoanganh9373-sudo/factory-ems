package com.ems.tariff.service;

import com.ems.tariff.dto.*;

import java.time.OffsetDateTime;
import java.util.List;

public interface TariffService {

    List<TariffPlanDTO> list();

    TariffPlanDTO getById(Long id);

    TariffPlanDTO create(CreateTariffPlanReq req);

    TariffPlanDTO update(Long id, UpdateTariffPlanReq req);

    void delete(Long id);

    ResolvedPriceDTO resolvePrice(Long energyTypeId, OffsetDateTime at);

    String resolvePeriodType(Long energyTypeId, OffsetDateTime at);
}
