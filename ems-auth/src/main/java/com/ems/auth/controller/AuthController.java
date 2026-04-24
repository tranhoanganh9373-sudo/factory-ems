package com.ems.auth.controller;

import com.ems.auth.dto.LoginReq;
import com.ems.auth.dto.LoginResp;
import com.ems.auth.service.AuthService;
import com.ems.core.dto.Result;
import com.ems.core.exception.UnauthorizedException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String COOKIE_REFRESH = "emsRefresh";

    private final AuthService auth;

    public AuthController(AuthService a) { this.auth = a; }

    @PostMapping("/login")
    public Result<LoginResp> login(@Valid @RequestBody LoginReq req,
                                   HttpServletRequest httpReq, HttpServletResponse resp) {
        var r = auth.login(req.username(), req.password(),
            httpReq.getRemoteAddr(), httpReq.getHeader("User-Agent"));
        writeRefreshCookie(resp, r.refreshToken(), r.refreshMaxAgeSeconds());
        return Result.ok(new LoginResp(r.accessToken(), r.accessExpSeconds(), r.user()));
    }

    @PostMapping("/refresh")
    public Result<LoginResp> refresh(HttpServletRequest req, HttpServletResponse resp) {
        String token = readRefreshCookie(req).orElseThrow(() ->
            new UnauthorizedException("no refresh token"));
        var r = auth.refresh(token, req.getRemoteAddr(), req.getHeader("User-Agent"));
        writeRefreshCookie(resp, r.refreshToken(), r.refreshMaxAgeSeconds());
        return Result.ok(new LoginResp(r.accessToken(), r.accessExpSeconds(), r.user()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest req, HttpServletResponse resp) {
        readRefreshCookie(req).ifPresent(auth::logout);
        clearRefreshCookie(resp);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<LoginResp.UserInfo> me(@AuthenticationPrincipal com.ems.auth.security.AuthUser u) {
        if (u == null) throw new UnauthorizedException("not logged in");
        return Result.ok(new LoginResp.UserInfo(u.getUserId(), u.getUsername(), null,
            u.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .toList()));
    }

    private java.util.Optional<String> readRefreshCookie(HttpServletRequest req) {
        if (req.getCookies() == null) return java.util.Optional.empty();
        return Arrays.stream(req.getCookies())
            .filter(c -> COOKIE_REFRESH.equals(c.getName()))
            .map(Cookie::getValue).findFirst();
    }

    private void writeRefreshCookie(HttpServletResponse resp, String token, long maxAgeSec) {
        Cookie c = new Cookie(COOKIE_REFRESH, token);
        c.setHttpOnly(true); c.setPath("/api/v1/auth");
        c.setMaxAge((int) maxAgeSec);
        c.setAttribute("SameSite", "Lax");
        resp.addCookie(c);
    }

    private void clearRefreshCookie(HttpServletResponse resp) {
        Cookie c = new Cookie(COOKIE_REFRESH, "");
        c.setHttpOnly(true); c.setPath("/api/v1/auth"); c.setMaxAge(0);
        resp.addCookie(c);
    }
}
