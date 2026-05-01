package com.ems.app.handler;

import com.ems.app.observability.AppMetrics;
import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {
        GlobalExceptionHandler.class,
        AppMetrics.class,
        GlobalExceptionHandlerTest.TestCtl.class,
        GlobalExceptionHandlerTest.TestCfg.class
})
class GlobalExceptionHandlerTest {

    @Autowired GlobalExceptionHandler handler;
    @Autowired TestCtl ctl;

    @Test
    void notFound_returns404() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(ctl).setControllerAdvice(handler).build();
        mvc.perform(get("/not-found"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value(40004));
    }

    @Test
    void biz400_returns400() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(ctl).setControllerAdvice(handler).build();
        mvc.perform(get("/biz-err"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value(40000));
    }

    @Configuration
    static class TestCfg {
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }

    @RestController
    static class TestCtl {
        @GetMapping("/not-found") public String nf() { throw new NotFoundException("User", 42); }
        @GetMapping("/biz-err") public String biz() { throw new BusinessException(40000, "bad"); }
    }
}
