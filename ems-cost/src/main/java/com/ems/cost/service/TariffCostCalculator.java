package com.ems.cost.service;

import com.ems.tariff.service.HourPrice;
import com.ems.tariff.service.TariffPriceLookupService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具类：把"每小时用量 × 该小时的电价段"换算成 4 段 quantity + 4 段 amount + 总额。
 * 算法层（Direct/Proportional/Residual/Composite）调用本类填 CostAllocationLine 的 *_quantity / *_amount。
 *
 * MVP 范围: 只对 ELEC 拆 4 段；非电品类调用方传 priceFlat → 全部计入 flat_*。
 */
public final class TariffCostCalculator {

    private TariffCostCalculator() {}

    /** ELEC: 用每小时用量 + tariff lookup 拆 4 段。 */
    public static Split splitByTariff(List<MeterUsageReader.HourlyUsage> hourly,
                                      List<HourPrice> hourPrices) {
        Map<OffsetDateTime, HourPrice> byHour = new HashMap<>(hourPrices.size() * 2);
        for (HourPrice hp : hourPrices) byHour.put(hp.hourStart(), hp);

        BigDecimal sharpQ = BigDecimal.ZERO, peakQ = BigDecimal.ZERO,
                   flatQ  = BigDecimal.ZERO, valleyQ = BigDecimal.ZERO;
        BigDecimal sharpA = BigDecimal.ZERO, peakA = BigDecimal.ZERO,
                   flatA  = BigDecimal.ZERO, valleyA = BigDecimal.ZERO;

        for (MeterUsageReader.HourlyUsage hu : hourly) {
            HourPrice hp = byHour.get(hu.hourTs());
            BigDecimal qty = hu.sumValue();
            BigDecimal amt;
            String type;
            if (hp == null) {
                type = "FLAT";
                amt = BigDecimal.ZERO;
            } else {
                type = hp.periodType();
                amt = qty.multiply(hp.pricePerUnit());
            }
            switch (type) {
                case "SHARP"  -> { sharpQ = sharpQ.add(qty); sharpA = sharpA.add(amt); }
                case "PEAK"   -> { peakQ  = peakQ.add(qty);  peakA  = peakA.add(amt);  }
                case "VALLEY" -> { valleyQ = valleyQ.add(qty); valleyA = valleyA.add(amt); }
                default       -> { flatQ  = flatQ.add(qty);  flatA  = flatA.add(amt);  }
            }
        }

        return new Split(
                scale4(sharpQ), scale4(peakQ), scale4(flatQ), scale4(valleyQ),
                scale4(sharpA), scale4(peakA), scale4(flatA), scale4(valleyA));
    }

    /** 非电品类：固定单价、不分段，全部计入 flat。 */
    public static Split flatOnly(BigDecimal totalQty, BigDecimal pricePerUnit) {
        BigDecimal q = totalQty == null ? BigDecimal.ZERO : totalQty;
        BigDecimal price = pricePerUnit == null ? BigDecimal.ZERO : pricePerUnit;
        BigDecimal amt = q.multiply(price);
        return new Split(
                BigDecimal.ZERO, BigDecimal.ZERO, scale4(q), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, scale4(amt), BigDecimal.ZERO);
    }

    /**
     * 减法：按段相减（用于 RESIDUAL：source - sum(deductMeters)）。
     * 任一段相减出负数 → clamp 到 0，并记一个标志位让 caller 写 warn / error_message。
     */
    public static SubtractResult subtract(Split source, Split deducted) {
        BigDecimal sQ = source.sharpQuantity().subtract(deducted.sharpQuantity());
        BigDecimal pQ = source.peakQuantity().subtract(deducted.peakQuantity());
        BigDecimal fQ = source.flatQuantity().subtract(deducted.flatQuantity());
        BigDecimal vQ = source.valleyQuantity().subtract(deducted.valleyQuantity());
        BigDecimal sA = source.sharpAmount().subtract(deducted.sharpAmount());
        BigDecimal pA = source.peakAmount().subtract(deducted.peakAmount());
        BigDecimal fA = source.flatAmount().subtract(deducted.flatAmount());
        BigDecimal vA = source.valleyAmount().subtract(deducted.valleyAmount());

        boolean clamped =
                sQ.signum() < 0 || pQ.signum() < 0 || fQ.signum() < 0 || vQ.signum() < 0 ||
                sA.signum() < 0 || pA.signum() < 0 || fA.signum() < 0 || vA.signum() < 0;

        Split residual = new Split(
                scale4(sQ.max(BigDecimal.ZERO)),
                scale4(pQ.max(BigDecimal.ZERO)),
                scale4(fQ.max(BigDecimal.ZERO)),
                scale4(vQ.max(BigDecimal.ZERO)),
                scale4(sA.max(BigDecimal.ZERO)),
                scale4(pA.max(BigDecimal.ZERO)),
                scale4(fA.max(BigDecimal.ZERO)),
                scale4(vA.max(BigDecimal.ZERO))
        );
        return new SubtractResult(residual, clamped);
    }

    /** 两个 Split 按段相加（用于把多个 deduct meter 的 split 累加成一个 deductedSplit）。 */
    public static Split add(Split a, Split b) {
        return new Split(
                scale4(a.sharpQuantity().add(b.sharpQuantity())),
                scale4(a.peakQuantity().add(b.peakQuantity())),
                scale4(a.flatQuantity().add(b.flatQuantity())),
                scale4(a.valleyQuantity().add(b.valleyQuantity())),
                scale4(a.sharpAmount().add(b.sharpAmount())),
                scale4(a.peakAmount().add(b.peakAmount())),
                scale4(a.flatAmount().add(b.flatAmount())),
                scale4(a.valleyAmount().add(b.valleyAmount()))
        );
    }

    public static Split zero() {
        BigDecimal z = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        return new Split(z, z, z, z, z, z, z, z);
    }

    /** 把 split 按权重缩放（用于 PROPORTIONAL 把单 source 的 split 拆给多个 org）。 */
    public static Split scale(Split src, BigDecimal weight) {
        BigDecimal w = weight == null ? BigDecimal.ZERO : weight;
        return new Split(
                scale4(src.sharpQuantity().multiply(w)),
                scale4(src.peakQuantity().multiply(w)),
                scale4(src.flatQuantity().multiply(w)),
                scale4(src.valleyQuantity().multiply(w)),
                scale4(src.sharpAmount().multiply(w)),
                scale4(src.peakAmount().multiply(w)),
                scale4(src.flatAmount().multiply(w)),
                scale4(src.valleyAmount().multiply(w))
        );
    }

    private static BigDecimal scale4(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    /** 减法结果：residual + 是否触发负值 clamp（让 caller 写 warn）。 */
    public record SubtractResult(Split residual, boolean clamped) {}

    /** 4 段拆分结果。totalQuantity / totalAmount 是 4 段之和。 */
    public record Split(
            BigDecimal sharpQuantity,
            BigDecimal peakQuantity,
            BigDecimal flatQuantity,
            BigDecimal valleyQuantity,
            BigDecimal sharpAmount,
            BigDecimal peakAmount,
            BigDecimal flatAmount,
            BigDecimal valleyAmount
    ) {
        public BigDecimal totalQuantity() {
            return sharpQuantity.add(peakQuantity).add(flatQuantity).add(valleyQuantity);
        }
        public BigDecimal totalAmount() {
            return sharpAmount.add(peakAmount).add(flatAmount).add(valleyAmount);
        }
    }
}
