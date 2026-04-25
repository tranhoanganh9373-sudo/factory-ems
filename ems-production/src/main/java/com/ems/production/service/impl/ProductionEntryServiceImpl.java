package com.ems.production.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.core.exception.ForbiddenException;
import com.ems.core.exception.NotFoundException;
import com.ems.core.security.PermissionResolver;
import com.ems.production.dto.*;
import com.ems.production.entity.ProductionEntry;
import com.ems.production.repository.ProductionEntryRepository;
import com.ems.production.service.ProductionEntryService;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class ProductionEntryServiceImpl implements ProductionEntryService {

    private final ProductionEntryRepository repo;
    private final PermissionResolver permission;

    public ProductionEntryServiceImpl(ProductionEntryRepository repo, PermissionResolver permission) {
        this.repo = repo;
        this.permission = permission;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductionEntryDTO> search(LocalDate from, LocalDate to, Long orgNodeId, Pageable pageable) {
        Long userId = permission.currentUserId();
        Set<Long> visible = permission.visibleNodeIds(userId);
        boolean isAdmin = permission.hasAllNodes(visible);

        List<Long> nodeIds;
        if (isAdmin) {
            if (orgNodeId != null) {
                nodeIds = List.of(orgNodeId);
            } else {
                // admin with no filter: get all entries — use a large date range query
                // We'll handle the all-node admin case by passing null to a separate query path
                nodeIds = null;
            }
        } else {
            if (orgNodeId != null) {
                if (!visible.contains(orgNodeId)) {
                    throw new ForbiddenException("组织节点不可见: " + orgNodeId);
                }
                nodeIds = List.of(orgNodeId);
            } else {
                nodeIds = new ArrayList<>(visible);
            }
        }

        List<ProductionEntry> all;
        if (nodeIds != null) {
            all = repo.findByEntryDateBetweenAndOrgNodeIdInOrderByEntryDateDescIdDesc(from, to, nodeIds);
        } else {
            // admin, no orgNodeId filter — fetch all in date range
            all = repo.findByEntryDateBetweenOrderByEntryDateDescIdDesc(from, to);
        }

        List<ProductionEntryDTO> dtos = all.stream().map(this::toDTO).toList();
        int total = dtos.size();
        int pageNum = pageable.getPageNumber();
        int pageSize = pageable.getPageSize();
        int fromIdx = Math.min(pageNum * pageSize, total);
        int toIdx = Math.min(fromIdx + pageSize, total);
        return new PageImpl<>(dtos.subList(fromIdx, toIdx), pageable, total);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionEntryDTO getById(Long id) {
        return toDTO(repo.findById(id).orElseThrow(() -> new NotFoundException("ProductionEntry", id)));
    }

    @Override
    @Transactional
    @Audited(action = "CREATE", resourceType = "PRODUCTION_ENTRY", resourceIdExpr = "#result.id()")
    public ProductionEntryDTO create(CreateProductionEntryReq req) {
        checkVisibility(req.orgNodeId());
        if (repo.existsByOrgNodeIdAndShiftIdAndEntryDateAndProductCode(
                req.orgNodeId(), req.shiftId(), req.entryDate(), req.productCode())) {
            throw new BusinessException(ErrorCode.CONFLICT, "产量记录已存在");
        }
        ProductionEntry e = buildEntry(req);
        e.setCreatedBy(permission.currentUserId());
        repo.save(e);
        return toDTO(e);
    }

    @Override
    @Transactional
    @Audited(action = "UPDATE", resourceType = "PRODUCTION_ENTRY", resourceIdExpr = "#id")
    public ProductionEntryDTO update(Long id, UpdateProductionEntryReq req) {
        ProductionEntry e = repo.findById(id).orElseThrow(() -> new NotFoundException("ProductionEntry", id));
        checkVisibility(e.getOrgNodeId());
        e.setQuantity(req.quantity());
        e.setUnit(req.unit());
        e.setRemark(req.remark());
        repo.save(e);
        return toDTO(e);
    }

    @Override
    @Transactional
    @Audited(action = "DELETE", resourceType = "PRODUCTION_ENTRY", resourceIdExpr = "#id")
    public void delete(Long id) {
        ProductionEntry e = repo.findById(id).orElseThrow(() -> new NotFoundException("ProductionEntry", id));
        checkVisibility(e.getOrgNodeId());
        repo.delete(e);
    }

    @Override
    @Audited(action = "BULK_IMPORT", resourceType = "PRODUCTION_ENTRY", summaryExpr = "'导入产量 CSV: ' + #csvFilename")
    public BulkImportResult importCsv(InputStream csvStream, String csvFilename) {
        List<BulkImportError> errors = new ArrayList<>();
        int total = 0;
        int succeeded = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream, StandardCharsets.UTF_8))) {
            String header = reader.readLine(); // skip header
            if (header == null) {
                return new BulkImportResult(0, 0, errors);
            }
            String line;
            int rowNumber = 0;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                total++;
                try {
                    boolean ok = importRow(line, rowNumber);
                    if (ok) succeeded++;
                    else errors.add(new BulkImportError(rowNumber, "重复记录"));
                } catch (Exception ex) {
                    errors.add(new BulkImportError(rowNumber, ex.getMessage()));
                }
            }
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC, "CSV 读取失败: " + ex.getMessage());
        }

        return new BulkImportResult(total, succeeded, errors);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean importRow(String line, int rowNumber) {
        // CSV columns: org_node_id,shift_id,entry_date,product_code,quantity,unit,remark
        String[] cols = line.split(",", -1);
        if (cols.length < 6) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "列数不足，期望至少6列");
        }
        // Check for quoted commas (simple rejection)
        for (String col : cols) {
            if (col.trim().startsWith("\"")) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持含逗号的带引号字段");
            }
        }

        Long orgNodeId;
        Long shiftId;
        LocalDate entryDate;
        String productCode;
        BigDecimal quantity;
        String unit;
        String remark;

        try {
            orgNodeId = Long.parseLong(cols[0].trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "org_node_id 格式错误: " + cols[0].trim());
        }
        try {
            shiftId = Long.parseLong(cols[1].trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "shift_id 格式错误: " + cols[1].trim());
        }
        try {
            entryDate = LocalDate.parse(cols[2].trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "entry_date 格式错误: " + cols[2].trim());
        }
        productCode = cols[3].trim();
        if (productCode.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "product_code 不能为空");
        }
        try {
            quantity = new BigDecimal(cols[4].trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "quantity 格式错误: " + cols[4].trim());
        }
        if (quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "quantity 不能为负数");
        }
        unit = cols[5].trim();
        if (unit.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "unit 不能为空");
        }
        remark = cols.length > 6 ? cols[6].trim() : null;

        if (repo.existsByOrgNodeIdAndShiftIdAndEntryDateAndProductCode(orgNodeId, shiftId, entryDate, productCode)) {
            return false;
        }

        ProductionEntry e = new ProductionEntry();
        e.setOrgNodeId(orgNodeId);
        e.setShiftId(shiftId);
        e.setEntryDate(entryDate);
        e.setProductCode(productCode);
        e.setQuantity(quantity);
        e.setUnit(unit);
        e.setRemark(remark);
        e.setCreatedBy(permission.currentUserId());
        repo.save(e);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<LocalDate, BigDecimal> dailyTotals(Long orgNodeId, LocalDate from, LocalDate to) {
        List<ProductionEntry> entries = repo.findByEntryDateBetweenAndOrgNodeIdInOrderByEntryDateDescIdDesc(
                from, to, List.of(orgNodeId));

        Map<LocalDate, BigDecimal> result = new TreeMap<>();
        // Pre-fill all dates with 0
        LocalDate d = from;
        while (!d.isAfter(to)) {
            result.put(d, BigDecimal.ZERO);
            d = d.plusDays(1);
        }
        for (ProductionEntry e : entries) {
            result.merge(e.getEntryDate(), e.getQuantity(), BigDecimal::add);
        }
        return result;
    }

    // ---- helpers ----

    private void checkVisibility(Long orgNodeId) {
        Long userId = permission.currentUserId();
        Set<Long> visible = permission.visibleNodeIds(userId);
        if (!permission.hasAllNodes(visible) && !visible.contains(orgNodeId)) {
            throw new ForbiddenException("组织节点不可见: " + orgNodeId);
        }
    }

    private ProductionEntry buildEntry(CreateProductionEntryReq req) {
        ProductionEntry e = new ProductionEntry();
        e.setOrgNodeId(req.orgNodeId());
        e.setShiftId(req.shiftId());
        e.setEntryDate(req.entryDate());
        e.setProductCode(req.productCode());
        e.setQuantity(req.quantity());
        e.setUnit(req.unit());
        e.setRemark(req.remark());
        return e;
    }

    private ProductionEntryDTO toDTO(ProductionEntry e) {
        return new ProductionEntryDTO(
                e.getId(), e.getOrgNodeId(), e.getShiftId(), e.getEntryDate(),
                e.getProductCode(), e.getQuantity(), e.getUnit(), e.getRemark(),
                e.getCreatedAt());
    }
}
