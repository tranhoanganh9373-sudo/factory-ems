package com.ems.cost.service.impl;

import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationLine;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.service.AllocationContext;
import com.ems.cost.service.AllocationStrategy;
import com.ems.cost.service.MeterUsageReader;
import com.ems.cost.service.TariffCostCalculator;
import com.ems.cost.service.WeightBasis;
import com.ems.cost.service.WeightResolver;
import com.ems.tariff.service.HourPrice;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * PROPORTIONAL: 把 source_meter 的总用量按 weights 分给 target_org_ids[]。
 * 4 段拆分: 先把 source_meter 的整段用量按小时分到 4 段 → 再按 weight scale 给每个 org。
 * 数学上等价于"每小时按 weight 分给每个 org 后按段累计"。
 */
@Component
public class ProportionalAllocationStrategy implements AllocationStrategy {

    private final WeightResolver weightResolver;

    public ProportionalAllocationStrategy(WeightResolver weightResolver) {
        this.weightResolver = weightResolver;
    }

    @Override
    public AllocationAlgorithm supports() {
        return AllocationAlgorithm.PROPORTIONAL;
    }

    @Override
    public List<CostAllocationLine> allocate(CostAllocationRule rule, AllocationContext ctx) {
        if (rule.getTargetOrgIds() == null || rule.getTargetOrgIds().length == 0) {
            throw new IllegalArgumentException("PROPORTIONAL rule must have at least 1 target_org_id: rule.code=" + rule.getCode());
        }

        Long meterId = rule.getSourceMeterId();
        Long energyTypeId = ctx.meterMetadata().energyTypeIdOf(meterId);

        List<MeterUsageReader.HourlyUsage> hourly =
                ctx.meterUsage().hourly(meterId, ctx.periodStart(), ctx.periodEnd());
        List<HourPrice> hourPrices =
                ctx.tariffLookup().batch(energyTypeId, ctx.periodStart(), ctx.periodEnd());
        TariffCostCalculator.Split sourceSplit = TariffCostCalculator.splitByTariff(hourly, hourPrices);

        WeightBasis basis = parseBasis(rule.getWeights());
        List<Long> orgList = Arrays.asList(rule.getTargetOrgIds());
        Map<Long, BigDecimal> weights = weightResolver.resolve(
                basis, orgList, rule.getWeights(), ctx.periodStart(), ctx.periodEnd());

        List<CostAllocationLine> out = new ArrayList<>(orgList.size());
        for (Long org : orgList) {
            BigDecimal w = weights.getOrDefault(org, BigDecimal.ZERO);
            TariffCostCalculator.Split split = TariffCostCalculator.scale(sourceSplit, w);

            CostAllocationLine line = new CostAllocationLine();
            line.setRuleId(rule.getId());
            line.setTargetOrgId(org);
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
            out.add(line);
        }
        return out;
    }

    private static WeightBasis parseBasis(Map<String, Object> weights) {
        if (weights == null) return WeightBasis.FIXED;
        Object basis = weights.get("basis");
        if (basis == null) return WeightBasis.FIXED;
        try {
            return WeightBasis.valueOf(basis.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return WeightBasis.FIXED;
        }
    }
}
