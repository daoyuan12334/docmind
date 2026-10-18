package com.docmind.config;

import com.docmind.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** MCP 端点自带独立鉴权（见 McpApiKeyFilter），因此放行给匿名，但只允许配置的路径。 */
    private static final String[] PUBLIC = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/public/**",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/mcp/**",
            "/error"
    };

    /** OpenAPI 文档与 Swagger UI 的静态资源路径，是否匿名可访问由 docmind.open-api.public 决定。 */
    private static final String[] OPENAPI_PATHS = {
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/swagger-ui/index.html"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter,
                                           com.docmind.mcp.McpApiKeyFilter mcpApiKeyFilter,
                                           AppProperties props) throws Exception {
        // public=true 时把文档路径并入匿名放行列表；否则走 ROLE_ADMIN。
        // 用拼接而非修改静态数组，避免不同 profile 下互相污染。
        String[] publicPaths = props.getOpenApi().isPublicAccess()
                ? java.util.stream.Stream.concat(java.util.Arrays.stream(PUBLIC),
                                                 java.util.Arrays.stream(OPENAPI_PATHS))
                                         .toArray(String[]::new)
                : PUBLIC;

        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"未认证或令牌已过期\"}");
                }))
                .authorizeHttpRequests(req -> req
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(publicPaths).permitAll()
                        .requestMatchers("/actuator/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(mcpApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
