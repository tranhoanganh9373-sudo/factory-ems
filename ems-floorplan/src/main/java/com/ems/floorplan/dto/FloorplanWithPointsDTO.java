package com.ems.floorplan.dto;

import java.util.List;

public record FloorplanWithPointsDTO(
        FloorplanDTO floorplan,
        List<FloorplanPointDTO> points
) {}
