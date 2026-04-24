package com.ems.auth.service.impl;

import com.ems.audit.event.AuditEvent;
import com.ems.audit.service.AuditService;
import com.ems.auth.dto.LoginResp;
import com.ems.auth.entity.*;
import com.ems.auth.jwt.JwtService;
import com.ems.auth.repository.*;
import com.ems.auth.service.AuthService;
import com.ems.core.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final RefreshTokenRepository refresh;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;

    @Value("${ems.login.max-failed-attempts}") private int maxFailed;
    @Value("${ems.login.lockout-minutes}")     private int lockoutMinutes;
    @Value("${ems.jwt.access-token-minutes}")  private long accessMinutes;
    @Value("${ems.jwt.refresh-token-days}")    private long refreshDays;

    public AuthServiceImpl(UserRepository u, UserRoleRepository ur, RefreshTokenRepository r,
                           PasswordEncoder e, JwtService j, AuditService a) {
        this.users = u; this.userRoles = ur; this.refresh = r;
        this.encoder = e; this.jwt = j; this.audit = a;
    }

    @Override
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResult login(String username, String password, String ip, String ua) {
        User u = users.findByUsername(username).orElseThrow(() ->
            authFail(null, username, ip, ua, "用户不存在"));

        if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(OffsetDateTime.now())) {
            audit(u.getId(), u.getUsername(), "LOGIN_FAIL", ip, ua, "账号锁定");
            throw new UnauthorizedException("账号已锁定，请稍后再试");
        }
        if (!Boolean.TRUE.equals(u.getEnabled())) {
            audit(u.getId(), u.getUsername(), "LOGIN_FAIL", ip, ua, "账号禁用");
            throw new UnauthorizedException("账号已禁用");
        }

        if (!encoder.matches(password, u.getPasswordHash())) {
            int n = (u.getFailedAttempts() == null ? 0 : u.getFailedAttempts()) + 1;
            if (n >= maxFailed) {
                u.setLockedUntil(OffsetDateTime.now().plusMinutes(lockoutMinutes));
                u.setFailedAttempts(0);
            } else {
                u.setFailedAttempts(n);
            }
            users.save(u);
            audit(u.getId(), u.getUsername(), "LOGIN_FAIL", ip, ua, "密码错误");
            throw new UnauthorizedException("用户名或密码错误");
        }

        // Login success
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        u.setLastLoginAt(OffsetDateTime.now());
        users.save(u);

        List<String> roles = userRoles.findRoleCodesByUserId(u.getId());
        String accessToken = jwt.signAccessToken(u.getId(), u.getUsername(), roles);
        JwtService.SignedRefresh refreshSigned = jwt.signRefreshToken(u.getId());

        RefreshToken rt = new RefreshToken();
        rt.setJti(refreshSigned.jti());
        rt.setUserId(u.getId());
        rt.setIssuedAt(OffsetDateTime.now());
        rt.setExpiresAt(refreshSigned.expiresAt().atOffset(ZoneOffset.UTC));
        refresh.save(rt);

        audit(u.getId(), u.getUsername(), "LOGIN", ip, ua, "登录成功");

        return new LoginResult(
            accessToken, accessMinutes * 60,
            refreshSigned.token(), refreshDays * 86400L,
            new LoginResp.UserInfo(u.getId(), u.getUsername(), u.getDisplayName(), roles)
        );
    }

    @Override
    @Transactional
    public LoginResult refresh(String refreshTokenStr, String ip, String ua) {
        JwtService.RefreshClaims c = jwt.parseRefresh(refreshTokenStr);
        RefreshToken rt = refresh.findById(c.jti()).orElseThrow(() ->
            new UnauthorizedException("refresh token not found"));
        if (rt.getRevokedAt() != null) throw new UnauthorizedException("refresh token revoked");
        if (rt.getExpiresAt().isBefore(OffsetDateTime.now())) throw new UnauthorizedException("refresh token expired");

        // Rotate: revoke old
        rt.setRevokedAt(OffsetDateTime.now());
        refresh.save(rt);

        User u = users.findById(c.userId()).orElseThrow(() -> new UnauthorizedException("user gone"));
        List<String> roles = userRoles.findRoleCodesByUserId(u.getId());
        String access = jwt.signAccessToken(u.getId(), u.getUsername(), roles);
        JwtService.SignedRefresh newRt = jwt.signRefreshToken(u.getId());
        RefreshToken nr = new RefreshToken();
        nr.setJti(newRt.jti()); nr.setUserId(u.getId());
        nr.setIssuedAt(OffsetDateTime.now());
        nr.setExpiresAt(newRt.expiresAt().atOffset(ZoneOffset.UTC));
        refresh.save(nr);

        return new LoginResult(access, accessMinutes * 60,
            newRt.token(), refreshDays * 86400L,
            new LoginResp.UserInfo(u.getId(), u.getUsername(), u.getDisplayName(), roles));
    }

    @Override
    @Transactional
    public void logout(String refreshTokenStr) {
        try {
            JwtService.RefreshClaims c = jwt.parseRefresh(refreshTokenStr);
            refresh.findById(c.jti()).ifPresent(rt -> {
                rt.setRevokedAt(OffsetDateTime.now());
                refresh.save(rt);
            });
            audit(c.userId(), null, "LOGOUT", null, null, "登出");
        } catch (Exception e) {
            log.debug("logout token invalid, ignore: {}", e.getMessage());
        }
    }

    private void audit(Long uid, String username, String action, String ip, String ua, String summary) {
        audit.record(new AuditEvent(uid, username, action, "AUTH", username,
            summary, null, ip, ua, OffsetDateTime.now()));
    }

    private UnauthorizedException authFail(Long uid, String un, String ip, String ua, String reason) {
        audit(uid, un, "LOGIN_FAIL", ip, ua, reason);
        return new UnauthorizedException("用户名或密码错误");
    }
}
