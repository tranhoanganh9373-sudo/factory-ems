package com.ems.auth.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

public class AuthUser extends User {
    private final Long userId;

    public AuthUser(Long userId, String username, String passwordHash,
                    boolean enabled, boolean accountNonLocked,
                    Collection<SimpleGrantedAuthority> authorities) {
        super(username, passwordHash, enabled, true, true, accountNonLocked, authorities);
        this.userId = userId;
    }

    public Long getUserId() { return userId; }
}
