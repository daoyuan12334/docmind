package com.docmind.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** 认证主体：尽量轻量，只带业务必需的三个字段。 */
public class AuthUser implements UserDetails {

    private final long userId;
    private final String username;
    private final String role;

    public AuthUser(long userId, String username, String role) {
        this.userId = userId;
        this.username = username;
        this.role = role == null ? "MEMBER" : role;
    }

    public long userId() {
        return userId;
    }

    public String role() {
        return role;
    }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
