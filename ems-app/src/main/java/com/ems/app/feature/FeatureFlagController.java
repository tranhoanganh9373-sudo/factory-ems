package com.ems.app.feature;

import com.ems.core.config.PvFeatureProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/features")
public class FeatureFlagController {

    private final PvFeatureProperties pv;

    public FeatureFlagController(PvFeatureProperties pv) {
        this.pv = pv;
    }

    @GetMapping
    public Map<String, Object> all() {
        return Map.of("pv", pv.enabled());
    }
}
