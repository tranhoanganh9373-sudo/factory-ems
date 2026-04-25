package com.ems.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProductionSumDTO(Long orgNodeId, Long shiftId, LocalDate entryDate, BigDecimal totalQuantity) {}
