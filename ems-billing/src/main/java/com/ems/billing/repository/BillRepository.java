package com.ems.billing.repository;

import com.ems.billing.entity.Bill;
import com.ems.cost.entity.EnergyTypeCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BillRepository extends JpaRepository<Bill, Long> {

    List<Bill> findByPeriodId(Long periodId);

    List<Bill> findByPeriodIdAndOrgNodeId(Long periodId, Long orgNodeId);

    List<Bill> findByPeriodIdAndOrgNodeIdIn(Long periodId, List<Long> orgNodeIds);

    Optional<Bill> findByPeriodIdAndOrgNodeIdAndEnergyType(
            Long periodId, Long orgNodeId, EnergyTypeCode energyType);

    @Query("SELECT b FROM Bill b WHERE b.periodId = :periodId AND b.energyType = :energyType")
    List<Bill> findByPeriodAndEnergy(
            @Param("periodId") Long periodId,
            @Param("energyType") EnergyTypeCode energyType);

    /** 重写策略：CLOSED→CLOSED 时先删该账期所有 bill（bill_line 经 FK CASCADE 级联清理）。 */
    @Modifying
    @Query("DELETE FROM Bill b WHERE b.periodId = :periodId")
    int deleteByPeriodId(@Param("periodId") Long periodId);
}
