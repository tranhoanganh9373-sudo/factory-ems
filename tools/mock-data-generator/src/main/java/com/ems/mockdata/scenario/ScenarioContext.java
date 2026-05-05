package com.ems.mockdata.scenario;

import com.ems.mockdata.config.ScaleProfile;
import java.time.LocalDate;

public record ScenarioContext(
    ScaleProfile scale,
    int months,
    long seed,
    LocalDate startDate,
    String seedOnly,
    boolean reset,
    boolean noInflux
) {}
