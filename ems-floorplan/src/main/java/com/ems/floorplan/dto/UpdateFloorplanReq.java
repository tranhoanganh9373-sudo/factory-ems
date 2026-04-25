package com.ems.floorplan.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateFloorplanReq(
        @NotBlank String name,
        Boolean enabled
) {}
