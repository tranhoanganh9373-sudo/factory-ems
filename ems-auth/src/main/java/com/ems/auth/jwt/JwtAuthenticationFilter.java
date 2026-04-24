package com.ems.auth.jwt;

import com.ems.auth.security.AuthUser;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                JwtService.AccessClaims c = jwt.parseAccess(token);
                List<SimpleGrantedAuthority> authorities = c.roles().stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                AuthUser principal = new AuthUser(c.userId(), c.username(), "",
                    true, true, authorities);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal, null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                SecurityContextHolder.getContext().setAuthentication(auth);
                MDC.put("userId", String.valueOf(c.userId()));
            } catch (Exception e) {
                // token invalid → leave SecurityContext empty, @PreAuthorize will block
            }
        }
        try { chain.doFilter(req, res); }
        finally { MDC.remove("userId"); }
    }
}
