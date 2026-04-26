package com.ems.tariff.service.impl;

import com.ems.tariff.entity.TariffPeriod;
import com.ems.tariff.entity.TariffPlan;
import com.ems.tariff.repository.TariffPeriodRepository;
import com.ems.tariff.repository.TariffPlanRepository;
import com.ems.tariff.service.HourPrice;
import com.ems.tariff.service.TariffPriceLookupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TariffPriceLookupServiceImpl implements TariffPriceLookupService {

    private static final Logger log = LoggerFactory.getLogger(TariffPriceLookupServiceImpl.class);

    private final TariffPlanRepository plans;
    private final TariffPeriodRepository periods;

    public TariffPriceLookupServiceImpl(TariffPlanRepository plans, TariffPeriodRepository periods) {
        this.plans = plans;
        this.periods = periods;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HourPrice> batch(Long energyTypeId, OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        if (!periodStart.isBefore(periodEnd)) {
            return List.of();
        }

        // hour-aligned floor of start
        OffsetDateTime hour = periodStart.withMinute(0).withSecond(0).withNano(0);
        if (hour.isBefore(periodStart)) hour = hour.plusHours(1);

        // cache: date → (planId, periodList) so a 31-day window does at most 31 plan lookups
        Map<LocalDate, List<TariffPeriod>> dayPeriodsCache = new HashMap<>();

        List<HourPrice> out = new ArrayList<>();
        while (hour.isBefore(periodEnd)) {
            LocalDate day = hour.toLocalDate();
            List<TariffPeriod> dayPeriods = dayPeriodsCache.computeIfAbsent(day,
                    d -> resolveDayPeriods(energyTypeId, d));
            HourPrice hp = mapHour(hour, dayPeriods);
            out.add(hp);
            hour = hour.plusHours(1);
        }
        return out;
    }

    private List<TariffPeriod> resolveDayPeriods(Long energyTypeId, LocalDate day) {
        Optional<TariffPlan> plan = plans.findFirstActiveByEnergyTypeId(energyTypeId, day);
        if (plan.isEmpty()) {
            log.warn("No active TariffPlan for energyTypeId={} on {}", energyTypeId, day);
            return List.of();
        }
        return periods.findByPlanIdOrderByTimeStartAsc(plan.get().getId());
    }

    private HourPrice mapHour(OffsetDateTime hour, List<TariffPeriod> dayPeriods) {
        if (dayPeriods.isEmpty()) {
            return new HourPrice(hour, "FLAT", BigDecimal.ZERO);
        }
        LocalTime t = hour.toLocalTime();
        for (TariffPeriod p : dayPeriods) {
            if (periodContains(p.getTimeStart(), p.getTimeEnd(), t)) {
                return new HourPrice(hour, p.getPeriodType(), p.getPricePerUnit());
            }
        }
        log.warn("Hour {} did not fall into any TariffPeriod; defaulting to FLAT 0", hour);
        return new HourPrice(hour, "FLAT", BigDecimal.ZERO);
    }

    /**
     * Returns true if time {@code t} falls within [start, end).
     * When start > end the period crosses midnight: t >= start OR t < end.
     */
    public static boolean periodContains(LocalTime start, LocalTime end, LocalTime t) {
        if (start.isAfter(end)) {
            return !t.isBefore(start) || t.isBefore(end);
        }
        return !t.isBefore(start) && t.isBefore(end);
    }
}
