package com.ems.report.controller;

import com.ems.report.carbon.CarbonReportDTO;
import com.ems.report.carbon.CarbonReportService;
import com.ems.timeseries.model.TimeRange;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/report")
public class CarbonController {

    private final CarbonReportService carbon;

    public CarbonController(CarbonReportService carbon) { this.carbon = carbon; }

    @GetMapping("/carbon")
    public CarbonReportDTO carbon(@RequestParam Long orgNodeId,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return carbon.compute(orgNodeId, new TimeRange(from, to));
    }
}
