package com.ems.auth.security;

import com.ems.auth.jwt.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Map;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public AuthenticationEntryPoint unauthorizedEntryPoint(ObjectMapper mapper) {
        return (req, res, ex) -> {
            res.setStatus(401);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getWriter(), Map.of("code", 40001, "message", "Unauthorized"));
        };
    }

    @Bean
    public SecurityFilterChain filter(HttpSecurity http,
                                      JwtAuthenticationFilter jwtFilter,
                                      AuthenticationEntryPoint entryPoint) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> {})   // provided by CorsFilter bean
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/actuator/prometheus").permitAll()
                // Floorplan images: 必须公开。原生 <img src> 不会带 Authorization 头，
                // CSS background-image 同理。floorplan 是工厂平面图，由车间地图照片构成，
                // 在已登录用户的 UI 内显示，不属于敏感数据。
                .requestMatchers(HttpMethod.GET, "/api/v1/floorplans/*/image").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public DaoAuthenticationProvider authProvider(UserDetailsService uds, PasswordEncoder enc) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(uds);
        p.setPasswordEncoder(enc);
        return p;
    }

    @Bean
    public AuthenticationManager authManager(DaoAuthenticationProvider p) {
        return a -> p.authenticate(a);
    }
}
