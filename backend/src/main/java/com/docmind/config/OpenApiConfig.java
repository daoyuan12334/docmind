package com.docmind.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.1 文档配置。
 *
 * <p>仅在 {@code docmind.open-api.enabled=true} 时装配，prod profile 默认关闭。
 * 认证统一走 {@code Authorization: Bearer <jwt>}，Swagger UI 右上角
 * “Authorize” 填入 {@code /api/auth/login} 返回的 token 即可联调。
 */
@Configuration
@ConditionalOnProperty(prefix = "docmind.open-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI docmindOpenAPI(AppProperties props) {
        var security = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .in(SecurityScheme.In.HEADER)
                .name("Authorization")
                .description("填入 /api/auth/login 返回的 token（无需手写 Bearer 前缀）");

        return new OpenAPI()
                .info(new Info()
                        .title("DocMind 私有文档智能系统 API")
                        .version("1.0.0")
                        .description("""
                                企业级 RAG 知识库平台：文档摄取 → 多模态解析 → 混合检索 → 生成式问答，
                                并对外提供 MCP 工具服务。

                                **鉴权**：除 `/api/auth/**` 与 `/actuator/health` 外，其余接口均需
                                `Authorization: Bearer <token>`。`/api/admin/**` 与 `/actuator/**`
                                额外要求 `ROLE_ADMIN`。

                                **限流**：默认每用户 30 次/分钟，超限返回 429 并带 `Retry-After`。
                                """)
                        .license(new License().name("Proprietary").url("https://example.com/license")))
                .components(new Components().addSecuritySchemes(BEARER, security))
                // 刻意不加全局 addSecurityItem：那样登录接口也会带锁，UI 上看着像“先有 token 才能登录”。
                // 改为在需要鉴权的控制器/方法上显式标 @SecurityRequirement(name = "bearer-jwt")。
                .addServersItem(new Server().url("/").description("当前部署"));
    }
}
