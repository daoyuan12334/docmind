package com.docmind.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 从 Authorization: Bearer &lt;token&gt; 还原身份；无 token 时放行，交由 Security 规则判定。 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (StringUtils.hasText(token)) {
                try {
                    var claims = jwtService.parse(token);
                    long userId = Long.parseLong(claims.getSubject());
                    String username = claims.get("username", String.class);
                    String role = claims.get("role", String.class);
                    AuthUser principal = new AuthUser(userId, username, role);
                    var auth = new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    CurrentUser.set(principal);
                } catch (Exception e) {
                    SecurityContextHolder.clearContext();
                }
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentUser.clear();
        }
    }
}
