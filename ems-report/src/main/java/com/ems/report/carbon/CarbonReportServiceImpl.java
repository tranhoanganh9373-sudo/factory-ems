package com.ems.report.carbon;

import com.ems.dashboard.service.SolarSelfConsumptionService;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.repository.CarbonFactorRepository;
import com.ems.timeseries.model.TimeRange;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class CarbonReportServiceImpl implements CarbonReportService {

    private final SolarSelfConsumptionService selfConsumption;
    private final CarbonFactorRepository carbonRepo;

    public CarbonReportServiceImpl(SolarSelfConsumptionService selfConsumption,
                                   CarbonFactorRepository carbonRepo) {
        this.selfConsumption = selfConsumption;
        this.carbonRepo = carbonRepo;
    }

    @Override
    public CarbonReportDTO compute(Long orgNodeId, TimeRange range) {
        var summary = selfConsumption.summarize(orgNodeId, range);

        // 硬编码区域 CN（后续可从 OrgNode 读取）
        String region = "CN";
        LocalDate asOf = range.end().atZone(ZoneOffset.UTC).toLocalDate();

        var gridFactor = carbonRepo.findEffective(region, EnergySource.GRID, asOf)
            .orElseThrow(() -> new IllegalStateException(
                "No CarbonFactor for region=" + region + " source=GRID asOf=" + asOf))
            .getFactorKgPerKwh();

        var solarFactor = carbonRepo.findEffective(region, EnergySource.SOLAR, asOf)
            .orElseThrow(() -> new IllegalStateException(
                "No CarbonFactor for region=" + region + " source=SOLAR asOf=" + asOf))
            .getFactorKgPerKwh();

        BigDecimal selfKwh = summary.selfConsumption();
        BigDecimal reductionKg = selfKwh.multiply(gridFactor.subtract(solarFactor));

        return new CarbonReportDTO(selfKwh, gridFactor, solarFactor, reductionKg);
    }
}
