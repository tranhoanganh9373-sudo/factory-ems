package com.ems.floorplan.service;

import com.ems.floorplan.dto.*;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface FloorplanService {

    List<FloorplanDTO> list(Long orgNodeId);

    FloorplanWithPointsDTO getById(Long id);

    FloorplanDTO upload(MultipartFile file, String name, Long orgNodeId);

    FloorplanDTO update(Long id, UpdateFloorplanReq req);

    void delete(Long id);

    FloorplanWithPointsDTO setPoints(Long floorplanId, SetPointsReq req);

    Resource loadImage(Long id);
}
