package com.ems.auth.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    JwtService jwt;

    @BeforeEach void setup() {
        jwt = new JwtService("this-is-a-test-secret-at-least-32-bytes-long!!!", 15, 7);
    }

    @Test
    void signAndParse_accessToken() {
        String token = jwt.signAccessToken(42L, "zhang3", List.of("VIEWER"));
        JwtService.AccessClaims c = jwt.parseAccess(token);
        assertThat(c.userId()).isEqualTo(42L);
        assertThat(c.username()).isEqualTo("zhang3");
        assertThat(c.roles()).containsExactly("VIEWER");
    }

    @Test
    void parseExpiredToken_throws() {
        JwtService shortLived = new JwtService("same-secret-32-bytes-long-for-test!!!", -1, 7);
        String token = shortLived.signAccessToken(1L, "x", List.of());
        try {
            shortLived.parseAccess(token);
            assertThat(false).isTrue();  // should not reach
        } catch (Exception e) {
            assertThat(e.getMessage()).containsIgnoringCase("expired");
        }
    }
}
