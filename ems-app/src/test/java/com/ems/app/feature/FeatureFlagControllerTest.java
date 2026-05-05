package com.ems.app.feature;

import com.ems.core.config.PvFeatureProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeatureFlagControllerTest {

    /** Shared permit-all security config to prevent JWT filter from loading. */
    @Configuration
    static class PermitAllSecurity {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
    }

    @Nested
    @WebMvcTest(controllers = FeatureFlagController.class)
    @ContextConfiguration(classes = {
            FeatureFlagController.class,
            FeatureFlagControllerTest.PermitAllSecurity.class,
            FeatureFlagControllerTest.EnabledConfig.class
    })
    class WhenPvEnabled {

        @Autowired MockMvc mvc;

        @Test
        void returnsPvFlag_enabled() throws Exception {
            mvc.perform(get("/api/v1/features"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.pv").value(true));
        }
    }

    @Nested
    @WebMvcTest(controllers = FeatureFlagController.class)
    @ContextConfiguration(classes = {
            FeatureFlagController.class,
            FeatureFlagControllerTest.PermitAllSecurity.class,
            FeatureFlagControllerTest.DisabledConfig.class
    })
    class WhenPvDisabled {

        @Autowired MockMvc mvc;

        @Test
        void returnsPvFlag_disabled() throws Exception {
            mvc.perform(get("/api/v1/features"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.pv").value(false));
        }
    }

    @Configuration
    static class EnabledConfig {
        @Bean
        PvFeatureProperties pv() { return new PvFeatureProperties(true); }
    }

    @Configuration
    static class DisabledConfig {
        @Bean
        PvFeatureProperties pv() { return new PvFeatureProperties(false); }
    }
}
