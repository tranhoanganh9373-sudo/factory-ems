package com.ems.production.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import com.ems.production.dto.CreateShiftReq;
import com.ems.production.dto.ShiftDTO;
import com.ems.production.dto.UpdateShiftReq;
import com.ems.production.entity.Shift;
import com.ems.production.repository.ProductionEntryRepository;
import com.ems.production.repository.ShiftRepository;
import com.ems.production.service.ShiftService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

@Service
public class ShiftServiceImpl implements ShiftService {

    private final ShiftRepository shifts;
    private final ProductionEntryRepository entries;

    public ShiftServiceImpl(ShiftRepository shifts, ProductionEntryRepository entries) {
        this.shifts = shifts;
        this.entries = entries;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShiftDTO> list(boolean enabledOnly) {
        List<Shift> result = enabledOnly
                ? shifts.findAllByEnabledTrueOrderBySortOrderAscIdAsc()
                : shifts.findAllByOrderBySortOrderAscIdAsc();
        return result.stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ShiftDTO getById(Long id) {
        return toDTO(shifts.findById(id).orElseThrow(() -> new NotFoundException("Shift", id)));
    }

    @Override
    @Transactional
    @Audited(action = "CREATE", resourceType = "SHIFT", resourceIdExpr = "#result.id()")
    public ShiftDTO create(CreateShiftReq req) {
        if (shifts.existsByCode(req.code())) {
            throw new BusinessException(ErrorCode.CONFLICT, "班次编码已存在: " + req.code());
        }
        Shift s = new Shift();
        s.setCode(req.code());
        s.setName(req.name());
        s.setTimeStart(req.timeStart());
        s.setTimeEnd(req.timeEnd());
        s.setEnabled(true);
        s.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        shifts.save(s);
        return toDTO(s);
    }

    @Override
    @Transactional
    @Audited(action = "UPDATE", resourceType = "SHIFT", resourceIdExpr = "#id")
    public ShiftDTO update(Long id, UpdateShiftReq req) {
        Shift s = shifts.findById(id).orElseThrow(() -> new NotFoundException("Shift", id));
        s.setName(req.name());
        s.setTimeStart(req.timeStart());
        s.setTimeEnd(req.timeEnd());
        if (req.enabled() != null) s.setEnabled(req.enabled());
        if (req.sortOrder() != null) s.setSortOrder(req.sortOrder());
        shifts.save(s);
        return toDTO(s);
    }

    @Override
    @Transactional
    @Audited(action = "DELETE", resourceType = "SHIFT", resourceIdExpr = "#id")
    public void delete(Long id) {
        Shift s = shifts.findById(id).orElseThrow(() -> new NotFoundException("Shift", id));
        if (entries.existsByShiftId(id)) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC, "班次仍被产量记录引用，无法删除");
        }
        shifts.delete(s);
    }

    /**
     * Returns true if time {@code t} falls within [start, end).
     * When start > end the shift crosses midnight: t >= start OR t < end.
     */
    public static boolean shiftContains(LocalTime start, LocalTime end, LocalTime t) {
        if (start.isAfter(end)) {
            return !t.isBefore(start) || t.isBefore(end);
        } else {
            return !t.isBefore(start) && t.isBefore(end);
        }
    }

    private ShiftDTO toDTO(Shift s) {
        return new ShiftDTO(
                s.getId(), s.getCode(), s.getName(),
                s.getTimeStart(), s.getTimeEnd(),
                Boolean.TRUE.equals(s.getEnabled()),
                s.getSortOrder() != null ? s.getSortOrder() : 0);
    }
}
