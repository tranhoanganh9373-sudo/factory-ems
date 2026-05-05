package com.ems.cost.service.impl;

import com.ems.cost.service.FeedInRevenueService;
import com.ems.cost.service.MeterUsageReader;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FeedInTariff;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.FeedInTariffRepository;
import com.ems.meter.repository.MeterRepository;
import com.ems.tariff.entity.PeriodType;
import com.ems.tariff.service.TariffService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 计算指定 org 在日期区间内的上网卖电（feed-in）收入。
 *
 * 算法：
 *  1. 查找 org 下 energySource 匹配且 flowDirection=EXPORT 的所有 meter
 *  2. 读取每个 meter 的小时级用量，通过 TariffService 按小时分类到 4 个时段
 *  3. 汇总每时段 kWh，乘以对应 FeedInTariff 价格
 *  4. 无 EXPORT meter 或用量为零 → 返回 ZERO
 *  5. 有用量但找不到对应 FeedInTariff 行 → IllegalStateException
 *
 * TODO (cross-date tariff): 当区间横跨多个 FeedInTariff 的 effective_from 边界时，
 * 当前实现以 `to` 日期查找最新生效价，未对区间内价格变更做分段处理。
 * 可在后续迭代中按 tariff.effectiveFrom 切分区间并分别计算。
 */
@Service
public class FeedInRevenueServiceImpl implements FeedInRevenueService {

    private static final String DEFAULT_REGION = "CN";
    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

    private final FeedInTariffRepository tariffRepo;
    private final MeterRepository meterRepository;
    private final MeterUsageReader usageReader;
    private final TariffService tariffService;

    public FeedInRevenueServiceImpl(FeedInTariffRepository tariffRepo,
                                    MeterRepository meterRepository,
                                    MeterUsageReader usageReader,
                                    TariffService tariffService) {
        this.tariffRepo = tariffRepo;
        this.meterRepository = meterRepository;
        this.usageReader = usageReader;
        this.tariffService = tariffService;
    }

    @Override
    public BigDecimal computeRevenue(Long orgNodeId, EnergySource source,
                                     LocalDate from, LocalDate to) {
        List<Meter> exportMeters = findExportMeters(orgNodeId, source);
        if (exportMeters.isEmpty()) {
            return BigDecimal.ZERO;
        }

        Map<PeriodType, BigDecimal> kwhByPeriod = aggregateExportByPeriod(exportMeters, from, to);
        if (kwhByPeriod.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal revenue = BigDecimal.ZERO;
        for (PeriodType period : PeriodType.values()) {
            BigDecimal kwh = kwhByPeriod.getOrDefault(period, BigDecimal.ZERO);
            if (kwh.signum() == 0) continue;

            FeedInTariff tariff = tariffRepo
                .findEffective(DEFAULT_REGION, source, period.name(), to)
                .orElseThrow(() -> new IllegalStateException(
                    "No FeedInTariff for region=" + DEFAULT_REGION
                    + " source=" + source
                    + " period=" + period
                    + " asOf=" + to));

            revenue = revenue.add(
                kwh.multiply(tariff.getPrice()).setScale(4, RoundingMode.HALF_UP));
        }

        return revenue;
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private List<Meter> findExportMeters(Long orgNodeId, EnergySource source) {
        return meterRepository.findByOrgNodeIdIn(List.of(orgNodeId)).stream()
            .filter(m -> m.getFlowDirection() == FlowDirection.EXPORT)
            .filter(m -> m.getEnergySource() == source)
            .toList();
    }

    /**
     * For each EXPORT meter, reads hourly usage over [from, to] (inclusive),
     * classifies each hour into a PeriodType via TariffService, and sums kWh per period.
     * Returns an empty map if total export is zero.
     */
    private Map<PeriodType, BigDecimal> aggregateExportByPeriod(List<Meter> exportMeters,
                                                                 LocalDate from,
                                                                 LocalDate to) {
        OffsetDateTime start = from.atStartOfDay().atOffset(ZONE);
        OffsetDateTime end   = to.plusDays(1).atStartOfDay().atOffset(ZONE);

        Map<PeriodType, BigDecimal> result = new EnumMap<>(PeriodType.class);

        for (Meter meter : exportMeters) {
            List<MeterUsageReader.HourlyUsage> hourly =
                usageReader.hourly(meter.getId(), start, end);

            for (MeterUsageReader.HourlyUsage hu : hourly) {
                if (hu.sumValue().signum() == 0) continue;

                String periodName = classifyHour(meter.getEnergyTypeId(), hu.hourTs());
                PeriodType period = parsePeriod(periodName);

                result.merge(period, hu.sumValue(), BigDecimal::add);
            }
        }

        return result;
    }

    /**
     * Classifies a single hour into a period name using TariffService.
     * Falls back to FLAT if no tariff plan is configured (returns null or throws).
     */
    private String classifyHour(Long energyTypeId, OffsetDateTime hourTs) {
        try {
            String p = tariffService.resolvePeriodType(energyTypeId, hourTs);
            return (p != null) ? p : "FLAT";
        } catch (Exception e) {
            // No tariff plan configured for this energy type → default to FLAT
            return "FLAT";
        }
    }

    /** Maps a period name string to PeriodType, defaulting to FLAT for unknowns. */
    private static PeriodType parsePeriod(String name) {
        if (name == null) return PeriodType.FLAT;
        return switch (name.toUpperCase()) {
            case "SHARP"  -> PeriodType.SHARP;
            case "PEAK"   -> PeriodType.PEAK;
            case "VALLEY" -> PeriodType.VALLEY;
            default       -> PeriodType.FLAT;
        };
    }
}
