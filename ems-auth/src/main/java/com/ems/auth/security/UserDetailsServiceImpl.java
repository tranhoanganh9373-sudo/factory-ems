package com.ems.auth.security;

import com.ems.auth.entity.User;
import com.ems.auth.repository.UserRepository;
import com.ems.auth.repository.UserRoleRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository users;
    private final UserRoleRepository userRoles;

    public UserDetailsServiceImpl(UserRepository u, UserRoleRepository ur) {
        this.users = u; this.userRoles = ur;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = users.findByUsername(username).orElseThrow(() ->
            new UsernameNotFoundException("no such user: " + username));

        List<String> codes = userRoles.findRoleCodesByUserId(u.getId());
        List<SimpleGrantedAuthority> authorities = codes.stream()
            .map(c -> new SimpleGrantedAuthority("ROLE_" + c)).toList();

        boolean locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(OffsetDateTime.now());

        return new AuthUser(u.getId(), u.getUsername(), u.getPasswordHash(),
            Boolean.TRUE.equals(u.getEnabled()), !locked, authorities);
    }
}
