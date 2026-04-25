package com.ems.tariff.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import com.ems.tariff.dto.*;
import com.ems.tariff.entity.PeriodType;
import com.ems.tariff.entity.TariffPeriod;
import com.ems.tariff.entity.TariffPlan;
import com.ems.tariff.repository.TariffPeriodRepository;
import com.ems.tariff.repository.TariffPlanRepository;
import com.ems.tariff.service.TariffService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

@Service
public class TariffServiceImpl implements TariffService {

    private final TariffPlanRepository plans;
    private final TariffPeriodRepository periods;

    public TariffServiceImpl(TariffPlanRepository plans, TariffPeriodRepository periods) {
        this.plans = plans;
        this.periods = periods;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TariffPlanDTO> list() {
        return plans.findAllByEnabledTrueOrderByEffectiveFromDesc()
                .stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TariffPlanDTO getById(Long id) {
        TariffPlan plan = plans.findById(id)
                .orElseThrow(() -> new NotFoundException("TariffPlan", id));
        return toDTO(plan);
    }

    @Override
    @Transactional
    @Audited(action = "CREATE", resourceType = "TARIFF_PLAN", resourceIdExpr = "#result.id()")
    public TariffPlanDTO create(CreateTariffPlanReq req) {
        if (plans.existsByName(req.name())) {
            throw new BusinessException(ErrorCode.CONFLICT, "电价方案名称已存在: " + req.name());
        }
        validatePeriodTypes(req.periods());

        TariffPlan plan = new TariffPlan();
        plan.setName(req.name());
        plan.setEnergyTypeId(req.energyTypeId());
        plan.setEffectiveFrom(req.effectiveFrom());
        plan.setEffectiveTo(req.effectiveTo());
        plan.setEnabled(true);
        plans.save(plan);

        savePeriodsForPlan(plan.getId(), req.periods());

        return toDTO(plan);
    }

    @Override
    @Transactional
    @Audited(action = "UPDATE", resourceType = "TARIFF_PLAN", resourceIdExpr = "#id")
    public TariffPlanDTO update(Long id, UpdateTariffPlanReq req) {
        TariffPlan plan = plans.findById(id)
                .orElseThrow(() -> new NotFoundException("TariffPlan", id));

        if (!plan.getName().equals(req.name()) && plans.existsByName(req.name())) {
            throw new BusinessException(ErrorCode.CONFLICT, "电价方案名称已存在: " + req.name());
        }

        if (req.periods() != null) {
            validatePeriodTypes(req.periods());
        }

        plan.setName(req.name());
        if (req.effectiveTo() != null) plan.setEffectiveTo(req.effectiveTo());
        if (req.enabled() != null) plan.setEnabled(req.enabled());
        plans.save(plan);

        if (req.periods() != null) {
            periods.deleteByPlanId(id);
            savePeriodsForPlan(id, req.periods());
        }

        return toDTO(plan);
    }

    @Override
    @Transactional
    @Audited(action = "DELETE", resourceType = "TARIFF_PLAN", resourceIdExpr = "#id")
    public void delete(Long id) {
        TariffPlan plan = plans.findById(id)
                .orElseThrow(() -> new NotFoundException("TariffPlan", id));
        // cascade DELETE on tariff_periods handled by DB; also delete via repo for JPA consistency
        periods.deleteByPlanId(id);
        plans.delete(plan);
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedPriceDTO resolvePrice(Long energyTypeId, OffsetDateTime at) {
        LocalDate date = at.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        TariffPlan plan = plans.findFirstActiveByEnergyTypeId(energyTypeId, date)
                .orElseThrow(() -> new NotFoundException("TariffPlan", "energyTypeId=" + energyTypeId + " at=" + at));

        LocalTime t = at.atZoneSameInstant(ZoneOffset.UTC).toLocalTime();
        List<TariffPeriod> periodList = periods.findByPlanIdOrderByTimeStartAsc(plan.getId());

        for (TariffPeriod p : periodList) {
            if (periodContains(p.getTimeStart(), p.getTimeEnd(), t)) {
                return new ResolvedPriceDTO(p.getPeriodType(), p.getPricePerUnit(), plan.getId());
            }
        }

        throw new NotFoundException("TariffPeriod", "no period covers time " + t + " in plan " + plan.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public String resolvePeriodType(Long energyTypeId, OffsetDateTime at) {
        try {
            return resolvePrice(energyTypeId, at).periodType();
        } catch (NotFoundException e) {
            return "FLAT";
        }
    }

    /**
     * Returns true if time {@code t} falls within [start, end).
     * When start > end the period crosses midnight: t >= start OR t < end.
     * When start <= end: t >= start AND t < end.
     */
    public static boolean periodContains(LocalTime start, LocalTime end, LocalTime t) {
        if (start.isAfter(end)) {
            // cross-midnight: e.g. 22:00 → 06:00
            return !t.isBefore(start) || t.isBefore(end);
        } else {
            return !t.isBefore(start) && t.isBefore(end);
        }
    }

    // ---- helpers ----

    private void validatePeriodTypes(List<CreateTariffPeriodReq> periodReqs) {
        if (periodReqs == null) return;
        for (CreateTariffPeriodReq r : periodReqs) {
            if (!PeriodType.isValid(r.periodType())) {
                throw new BusinessException(ErrorCode.BIZ_GENERIC,
                        "时段类型必须是 SHARP/PEAK/FLAT/VALLEY，当前值: " + r.periodType());
            }
        }
    }

    private void savePeriodsForPlan(Long planId, List<CreateTariffPeriodReq> periodReqs) {
        if (periodReqs == null || periodReqs.isEmpty()) return;
        for (CreateTariffPeriodReq r : periodReqs) {
            TariffPeriod p = new TariffPeriod();
            p.setPlanId(planId);
            p.setPeriodType(r.periodType());
            p.setTimeStart(r.timeStart());
            p.setTimeEnd(r.timeEnd());
            p.setPricePerUnit(r.pricePerUnit());
            periods.save(p);
        }
    }

    private TariffPlanDTO toDTO(TariffPlan plan) {
        List<TariffPeriodDTO> periodDTOs = periods.findByPlanIdOrderByTimeStartAsc(plan.getId())
                .stream()
                .map(p -> new TariffPeriodDTO(
                        p.getId(), p.getPeriodType(),
                        p.getTimeStart(), p.getTimeEnd(), p.getPricePerUnit()))
                .toList();
        return new TariffPlanDTO(
                plan.getId(), plan.getName(), plan.getEnergyTypeId(),
                plan.getEffectiveFrom(), plan.getEffectiveTo(),
                Boolean.TRUE.equals(plan.getEnabled()),
                periodDTOs, plan.getCreatedAt());
    }
}
