package com.ems.dashboard.controller;

import com.ems.dashboard.dto.EnergySourceMixDTO;
import com.ems.dashboard.dto.PvCurveDTO;
import com.ems.dashboard.service.DashboardService;
import com.ems.meter.entity.EnergySource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DashboardController.class,
            excludeAutoConfiguration = SecurityAutoConfiguration.class)
class DashboardControllerPvTest {

    @Autowired MockMvc mvc;
    @MockBean DashboardService service;

    @Test
    void energySourceMix_returnsJson() throws Exception {
        when(service.energySourceMix(any())).thenReturn(List.of(
            new EnergySourceMixDTO(EnergySource.GRID,  "kWh", 800.0, 0.8),
            new EnergySourceMixDTO(EnergySource.SOLAR, "kWh", 200.0, 0.2)
        ));
        mvc.perform(get("/api/v1/dashboard/energy-source-mix?orgNodeId=1&range=TODAY"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(2))
           .andExpect(jsonPath("$.data[0].energySource").value("GRID"));
    }

    @Test
    void pvCurve_returnsJson() throws Exception {
        when(service.pvCurve(any())).thenReturn(new PvCurveDTO("kWh", List.of(
            new PvCurveDTO.HourBucket(Instant.parse("2026-05-04T08:00:00Z"), 100.0, 50.0)
        )));
        mvc.perform(get("/api/v1/dashboard/pv-curve?orgNodeId=1&range=TODAY"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.unit").value("kWh"))
           .andExpect(jsonPath("$.data.buckets[0].generation").value(100.0));
    }
}
