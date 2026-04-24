package com.ems.auth.jwt;

import com.ems.core.exception.UnauthorizedException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {

    public record AccessClaims(Long userId, String username, List<String> roles) {}
    public record RefreshClaims(String jti, Long userId, Instant expiresAt) {}
    public record SignedRefresh(String token, String jti, Instant expiresAt) {}

    private final SecretKey key;
    private final long accessMinutes;
    private final long refreshDays;

    public JwtService(@Value("${ems.jwt.secret}") String secret,
                      @Value("${ems.jwt.access-token-minutes}") long accessMinutes,
                      @Value("${ems.jwt.refresh-token-days}") long refreshDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessMinutes = accessMinutes;
        this.refreshDays   = refreshDays;
    }

    public String signAccessToken(Long userId, String username, List<String> roles) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessMinutes * 60);
        return Jwts.builder()
            .subject(username)
            .claim("uid", userId)
            .claim("roles", roles)
            .claim("typ", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key)
            .compact();
    }

    public SignedRefresh signRefreshToken(Long userId) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(refreshDays * 86400L);
        String token = Jwts.builder()
            .id(jti).claim("uid", userId).claim("typ", "refresh")
            .issuedAt(Date.from(now)).expiration(Date.from(exp))
            .signWith(key).compact();
        return new SignedRefresh(token, jti, exp);
    }

    public AccessClaims parseAccess(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!"access".equals(c.get("typ"))) throw new UnauthorizedException("wrong token type");
            Long uid = c.get("uid", Long.class);
            @SuppressWarnings("unchecked")
            List<String> roles = c.get("roles", List.class);
            return new AccessClaims(uid, c.getSubject(), roles);
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException("token expired");
        } catch (JwtException e) {
            throw new UnauthorizedException("invalid token");
        }
    }

    public RefreshClaims parseRefresh(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!"refresh".equals(c.get("typ"))) throw new UnauthorizedException("wrong token type");
            Long uid = c.get("uid", Long.class);
            return new RefreshClaims(c.getId(), uid, c.getExpiration().toInstant());
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException("refresh token expired");
        } catch (JwtException e) {
            throw new UnauthorizedException("invalid refresh token");
        }
    }
}
