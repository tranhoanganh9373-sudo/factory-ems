package com.ems.production.service;

import com.ems.production.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public interface ProductionEntryService {

    Page<ProductionEntryDTO> search(LocalDate from, LocalDate to, Long orgNodeId, Pageable pageable);

    ProductionEntryDTO getById(Long id);

    ProductionEntryDTO create(CreateProductionEntryReq req);

    ProductionEntryDTO update(Long id, UpdateProductionEntryReq req);

    void delete(Long id);

    BulkImportResult importCsv(InputStream csvStream, String csvFilename);

    Map<LocalDate, BigDecimal> dailyTotals(Long orgNodeId, LocalDate from, LocalDate to);
}
