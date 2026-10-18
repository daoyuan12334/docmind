package com.docmind.mcp;

import com.docmind.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * MCP 端点鉴权。未配置密钥时不拦截（方便本机联调）；
 * 一旦配置了 docmind.mcp.api-key，必须携带 X-MCP-ApiKey 才允许调用。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RequiredArgsConstructor
public class McpApiKeyFilter extends OncePerRequestFilter {

    private final AppProperties props;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/mcp")) {
            chain.doFilter(request, response);
            return;
        }
        String expected = props.getMcp().getApiKey();
        if (!StringUtils.hasText(expected)) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader("X-MCP-ApiKey");
        if (!expected.equals(provided)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"MCP 密钥无效\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
