package com.ems.cost.service.impl;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.service.AllocationContext;
import com.ems.cost.service.AllocationStrategy;
import com.ems.cost.service.MeterUsageReader;
import com.ems.cost.service.TariffCostCalculator;
import com.ems.tariff.service.HourPrice;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DIRECT: 整个 source_meter 的量归 target_org_ids[0]。
 * "这块表就是 X 车间专用的"。
 */
@Component
public class DirectAllocationStrategy implements AllocationStrategy {

    @Override
    public AllocationAlgorithm supports() {
        return AllocationAlgorithm.DIRECT;
    }

    @Override
    public List<CostAllocationLine> allocate(CostAllocationRule rule, AllocationContext ctx) {
        if (rule.getTargetOrgIds() == null || rule.getTargetOrgIds().length == 0) {
            throw new IllegalArgumentException("DIRECT rule must have at least 1 target_org_id: rule.code=" + rule.getCode());
        }
        Long targetOrg = rule.getTargetOrgIds()[0];
        Long meterId   = rule.getSourceMeterId();
        Long energyTypeId = ctx.meterMetadata().energyTypeIdOf(meterId);

        List<MeterUsageReader.HourlyUsage> hourly =
                ctx.meterUsage().hourly(meterId, ctx.periodStart(), ctx.periodEnd());
        List<HourPrice> hourPrices =
                ctx.tariffLookup().batch(energyTypeId, ctx.periodStart(), ctx.periodEnd());

        TariffCostCalculator.Split split = TariffCostCalculator.splitByTariff(hourly, hourPrices);

        CostAllocationLine line = new CostAllocationLine();
        line.setRuleId(rule.getId());
        line.setTargetOrgId(targetOrg);
        line.setEnergyType(rule.getEnergyType());
        line.setQuantity(split.totalQuantity());
        line.setAmount(split.totalAmount());
        line.setSharpQuantity(split.sharpQuantity());
        line.setPeakQuantity(split.peakQuantity());
        line.setFlatQuantity(split.flatQuantity());
        line.setValleyQuantity(split.valleyQuantity());
        line.setSharpAmount(split.sharpAmount());
        line.setPeakAmount(split.peakAmount());
        line.setFlatAmount(split.flatAmount());
        line.setValleyAmount(split.valleyAmount());
        return List.of(line);
    }
}
