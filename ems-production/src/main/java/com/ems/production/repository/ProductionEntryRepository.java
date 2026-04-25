package com.ems.production.repository;

import com.ems.production.dto.ProductionSumDTO;
import com.ems.production.entity.ProductionEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ProductionEntryRepository extends JpaRepository<ProductionEntry, Long> {

    boolean existsByOrgNodeIdAndShiftIdAndEntryDateAndProductCode(
            Long orgNodeId, Long shiftId, LocalDate entryDate, String productCode);

    List<ProductionEntry> findByEntryDateBetweenAndOrgNodeIdInOrderByEntryDateDescIdDesc(
            LocalDate from, LocalDate to, Collection<Long> orgNodeIds);

    List<ProductionEntry> findByEntryDateAndOrgNodeId(LocalDate date, Long orgNodeId);

    @Query("SELECT new com.ems.production.dto.ProductionSumDTO(p.orgNodeId, p.shiftId, p.entryDate, SUM(p.quantity)) " +
           "FROM ProductionEntry p " +
           "WHERE p.entryDate BETWEEN :from AND :to " +
           "AND p.orgNodeId IN :orgNodeIds " +
           "GROUP BY p.orgNodeId, p.shiftId, p.entryDate")
    List<ProductionSumDTO> sumQuantityByOrgNodeIdInAndEntryDateBetween(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("orgNodeIds") Collection<Long> orgNodeIds);

    boolean existsByShiftId(Long shiftId);

    List<ProductionEntry> findByEntryDateBetweenOrderByEntryDateDescIdDesc(LocalDate from, LocalDate to);
}
