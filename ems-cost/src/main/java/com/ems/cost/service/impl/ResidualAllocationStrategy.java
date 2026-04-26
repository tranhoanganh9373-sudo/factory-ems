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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * RESIDUAL: 主表总量 - sum(deductMeters)，剩下的（公摊：照明 / 损耗 / 未计量）按 weights 分给 target_org_ids。
 *
 * weights JSONB 结构：
 *   {
 *     "deductMeterIds": [123, 124, 125],
 *     "basis": "FIXED|AREA|HEADCOUNT|PRODUCTION",   // 残差如何分配
 *     "values": { "10": 0.4, "20": 0.6 }            // basis=FIXED 时
 *   }
 *
 * 负残差 → 按段 clamp 到 0，写 warn，run.error_message 由上层补；MVP 不做按比例缩 deductMeter。
 */
@Component
public class ResidualAllocationStrategy implements AllocationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ResidualAllocationStrategy.class);

    private final WeightResolver weightResolver;

    public ResidualAllocationStrategy(WeightResolver weightResolver) {
        this.weightResolver = weightResolver;
    }

    @Override
    public AllocationAlgorithm supports() {
        return AllocationAlgorithm.RESIDUAL;
    }

    @Override
    public List<CostAllocationLine> allocate(CostAllocationRule rule, AllocationContext ctx) {
        if (rule.getTargetOrgIds() == null || rule.getTargetOrgIds().length == 0) {
            throw new IllegalArgumentException("RESIDUAL rule must have at least 1 target_org_id: rule.code=" + rule.getCode());
        }

        Long energyTypeId = ctx.meterMetadata().energyTypeIdOf(rule.getSourceMeterId());
        List<HourPrice> hourPrices = ctx.tariffLookup().batch(energyTypeId, ctx.periodStart(), ctx.periodEnd());

        // 1) source split
        List<MeterUsageReader.HourlyUsage> sourceHourly =
                ctx.meterUsage().hourly(rule.getSourceMeterId(), ctx.periodStart(), ctx.periodEnd());
        TariffCostCalculator.Split sourceSplit = TariffCostCalculator.splitByTariff(sourceHourly, hourPrices);

        // 2) accumulate deduct meters split
        TariffCostCalculator.Split deducted = TariffCostCalculator.zero();
        List<Long> deductIds = parseDeductMeterIds(rule.getWeights());
        for (Long mid : deductIds) {
            List<MeterUsageReader.HourlyUsage> mh =
                    ctx.meterUsage().hourly(mid, ctx.periodStart(), ctx.periodEnd());
            TariffCostCalculator.Split ds = TariffCostCalculator.splitByTariff(mh, hourPrices);
            deducted = TariffCostCalculator.add(deducted, ds);
        }

        // 3) residual = source - deducted; clamp negatives
        TariffCostCalculator.SubtractResult sub = TariffCostCalculator.subtract(sourceSplit, deducted);
        if (sub.clamped()) {
            log.warn("RESIDUAL rule.code={} produced negative band; clamped to 0. " +
                            "source.total={} deducted.total={}",
                    rule.getCode(), sourceSplit.totalQuantity(), deducted.totalQuantity());
        }
        TariffCostCalculator.Split residual = sub.residual();

        // 4) distribute residual among target_org_ids by weights
        WeightBasis basis = parseBasis(rule.getWeights());
        List<Long> orgList = Arrays.asList(rule.getTargetOrgIds());
        Map<Long, BigDecimal> weights = weightResolver.resolve(
                basis, orgList, rule.getWeights(), ctx.periodStart(), ctx.periodEnd());

        List<CostAllocationLine> out = new ArrayList<>(orgList.size());
        for (Long org : orgList) {
            BigDecimal w = weights.getOrDefault(org, BigDecimal.ZERO);
            TariffCostCalculator.Split share = TariffCostCalculator.scale(residual, w);

            CostAllocationLine line = new CostAllocationLine();
            line.setRuleId(rule.getId());
            line.setTargetOrgId(org);
            line.setEnergyType(rule.getEnergyType());
            line.setQuantity(share.totalQuantity());
            line.setAmount(share.totalAmount());
            line.setSharpQuantity(share.sharpQuantity());
            line.setPeakQuantity(share.peakQuantity());
            line.setFlatQuantity(share.flatQuantity());
            line.setValleyQuantity(share.valleyQuantity());
            line.setSharpAmount(share.sharpAmount());
            line.setPeakAmount(share.peakAmount());
            line.setFlatAmount(share.flatAmount());
            line.setValleyAmount(share.valleyAmount());
            out.add(line);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> parseDeductMeterIds(Map<String, Object> weights) {
        if (weights == null) return List.of();
        Object raw = weights.get("deductMeterIds");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Long> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o == null) continue;
            out.add(((Number) (o instanceof Number n ? n : Long.valueOf(o.toString()))).longValue());
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
