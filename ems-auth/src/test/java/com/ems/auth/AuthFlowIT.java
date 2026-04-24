package com.ems.auth;

import com.ems.auth.service.UserService;
import com.ems.auth.dto.CreateUserReq;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = AuthITApp.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthFlowIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mvc;

    @Test
    void login_wrongPassword_401() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void login_success_returnsAccessToken() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"admin123!\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
           .andExpect(cookie().exists("emsRefresh"));
    }

    @Test
    void protected_withoutToken_401() throws Exception {
        mvc.perform(get("/api/v1/users"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_rotatesToken() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"admin123!\"}"))
            .andExpect(status().isOk()).andReturn();
        Cookie refreshCookie = login.getResponse().getCookie("emsRefresh");
        String oldToken = refreshCookie.getValue();

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
            .andExpect(status().isOk()).andReturn();
        Cookie newCookie = refreshed.getResponse().getCookie("emsRefresh");

        assertThat(newCookie.getValue()).isNotEqualTo(oldToken);

        // old token should be revoked
        mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void lockoutAfter5FailedAttempts(@Autowired UserService userService) throws Exception {
        userService.create(new CreateUserReq("lockme", "password123", "Lock Me", List.of("VIEWER")));
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login")
                    .contentType("application/json")
                    .content("{\"username\":\"lockme\",\"password\":\"wrong\"}"))
               .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"lockme\",\"password\":\"password123\"}"))
           .andExpect(status().isUnauthorized());
    }
}
