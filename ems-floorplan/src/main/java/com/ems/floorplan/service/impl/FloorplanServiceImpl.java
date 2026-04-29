package com.ems.floorplan.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import com.ems.floorplan.dto.*;
import com.ems.floorplan.entity.Floorplan;
import com.ems.floorplan.entity.FloorplanPoint;
import com.ems.floorplan.repository.FloorplanPointRepository;
import com.ems.floorplan.repository.FloorplanRepository;
import com.ems.floorplan.service.FloorplanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class FloorplanServiceImpl implements FloorplanService {

    private static final Logger log = LoggerFactory.getLogger(FloorplanServiceImpl.class);

    private static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg");

    @Value("${ems.floorplan.base-dir:./ems_uploads/floorplans}")
    private String baseDir;

    @Value("${ems.floorplan.max-bytes:10485760}")
    private long maxBytes;

    private final FloorplanRepository floorplans;
    private final FloorplanPointRepository points;

    public FloorplanServiceImpl(FloorplanRepository floorplans, FloorplanPointRepository points) {
        this.floorplans = floorplans;
        this.points = points;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FloorplanDTO> list(Long orgNodeId) {
        if (orgNodeId != null) {
            return floorplans.findByOrgNodeIdAndEnabledTrueOrderByIdDesc(orgNodeId)
                    .stream().map(this::toDTO).toList();
        }
        return floorplans.findAllByEnabledTrueOrderByIdDesc()
                .stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FloorplanWithPointsDTO getById(Long id) {
        Floorplan fp = floorplans.findById(id)
                .orElseThrow(() -> new NotFoundException("Floorplan", id));
        return toWithPointsDTO(fp);
    }

    @Override
    @Transactional
    @Audited(action = "CREATE", resourceType = "FLOORPLAN", resourceIdExpr = "#result.id()")
    public FloorplanDTO upload(MultipartFile file, String name, Long orgNodeId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC, "上传文件不能为空");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC,
                    "不支持的文件类型: " + contentType + "，仅支持 image/png 和 image/jpeg");
        }

        long fileSize = file.getSize();
        if (fileSize > maxBytes) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC,
                    "文件大小超过限制: " + fileSize + " bytes，最大允许 " + maxBytes + " bytes");
        }

        // Read image dimensions
        int width;
        int height;
        try (InputStream in = file.getInputStream()) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new BusinessException(ErrorCode.BIZ_GENERIC, "无法读取图片内容，请确认文件格式正确");
            }
            width = img.getWidth();
            height = img.getHeight();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC, "读取图片失败: " + e.getMessage());
        }

        // Generate relative file path: yyyy/MM/{uuid}.{ext}
        String ext = contentType.equals("image/png") ? "png" : "jpg";
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String relativePath = datePart + "/" + UUID.randomUUID() + "." + ext;

        // Write file to disk
        Path target = Paths.get(baseDir).resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC, "保存图片失败: " + e.getMessage());
        }

        Floorplan fp = new Floorplan();
        fp.setName(name);
        fp.setOrgNodeId(orgNodeId);
        fp.setFilePath(relativePath);
        fp.setContentType(contentType);
        fp.setWidthPx(width);
        fp.setHeightPx(height);
        fp.setFileSizeBytes(fileSize);
        fp.setEnabled(true);
        floorplans.save(fp);

        return toDTO(fp);
    }

    @Override
    @Transactional
    @Audited(action = "UPDATE", resourceType = "FLOORPLAN", resourceIdExpr = "#id")
    public FloorplanDTO update(Long id, UpdateFloorplanReq req) {
        Floorplan fp = floorplans.findById(id)
                .orElseThrow(() -> new NotFoundException("Floorplan", id));
        fp.setName(req.name());
        if (req.enabled() != null) {
            fp.setEnabled(req.enabled());
        }
        floorplans.save(fp);
        return toDTO(fp);
    }

    @Override
    @Transactional
    @Audited(action = "DELETE", resourceType = "FLOORPLAN", resourceIdExpr = "#id")
    public void delete(Long id) {
        Floorplan fp = floorplans.findById(id)
                .orElseThrow(() -> new NotFoundException("Floorplan", id));
        String relativePath = fp.getFilePath();
        points.deleteByFloorplanId(id);
        floorplans.delete(fp);

        if (relativePath != null && !relativePath.isBlank()) {
            Path filePath = Paths.get(baseDir).resolve(relativePath);
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                // DB row already removed; log and continue rather than rolling back to a stale state
                log.warn("Failed to delete floorplan file: {}", filePath, e);
            }
        }
    }

    @Override
    @Transactional
    @Audited(action = "UPDATE_POINTS", resourceType = "FLOORPLAN", resourceIdExpr = "#floorplanId")
    public FloorplanWithPointsDTO setPoints(Long floorplanId, SetPointsReq req) {
        Floorplan fp = floorplans.findById(floorplanId)
                .orElseThrow(() -> new NotFoundException("Floorplan", floorplanId));

        // Validate no duplicate meterId in incoming list
        Set<Long> seen = new HashSet<>();
        for (SetPointsReq.PointEntry entry : req.points()) {
            if (!seen.add(entry.meterId())) {
                throw new BusinessException(ErrorCode.BIZ_GENERIC,
                        "点位列表中存在重复的 meterId: " + entry.meterId());
            }
        }

        // Replace all points
        points.deleteByFloorplanId(floorplanId);
        points.flush();

        for (SetPointsReq.PointEntry entry : req.points()) {
            FloorplanPoint pt = new FloorplanPoint();
            pt.setFloorplanId(floorplanId);
            pt.setMeterId(entry.meterId());
            pt.setXRatio(entry.xRatio());
            pt.setYRatio(entry.yRatio());
            pt.setLabel(entry.label());
            points.save(pt);
        }

        return toWithPointsDTO(fp);
    }

    @Override
    @Transactional(readOnly = true)
    public Resource loadImage(Long id) {
        Floorplan fp = floorplans.findById(id)
                .orElseThrow(() -> new NotFoundException("Floorplan", id));
        Path filePath = Paths.get(baseDir).resolve(fp.getFilePath());
        if (!Files.exists(filePath)) {
            throw new NotFoundException("Floorplan image file", fp.getFilePath());
        }
        return new FileSystemResource(filePath);
    }

    // ---- helpers ----

    private FloorplanDTO toDTO(Floorplan fp) {
        return new FloorplanDTO(
                fp.getId(),
                fp.getName(),
                fp.getOrgNodeId(),
                fp.getContentType(),
                fp.getWidthPx(),
                fp.getHeightPx(),
                fp.getFileSizeBytes(),
                Boolean.TRUE.equals(fp.getEnabled()),
                fp.getCreatedAt()
        );
    }

    private FloorplanWithPointsDTO toWithPointsDTO(Floorplan fp) {
        List<FloorplanPointDTO> pointDTOs = points.findByFloorplanIdOrderByIdAsc(fp.getId())
                .stream()
                .map(pt -> new FloorplanPointDTO(
                        pt.getId(),
                        pt.getMeterId(),
                        pt.getXRatio(),
                        pt.getYRatio(),
                        pt.getLabel()
                ))
                .toList();
        return new FloorplanWithPointsDTO(toDTO(fp), pointDTOs);
    }
}
