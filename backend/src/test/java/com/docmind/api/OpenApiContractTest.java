package com.docmind.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 文档契约。
 *
 * <p>守两件事：
 *
 * <ol>
 *   <li>文档端点真的能匿名打开 —— 一旦有人动 SecurityConfig 把它挡住，
 *       或把 {@code docmind.open-api.enabled} 调成 false，这里立刻变红；
 *   <li>鉴权标注没跑偏 —— 受保护接口必须带 security，登录/注册必须不带。
 *       这两条反了都是事故：前者意味着“文档里看不出要鉴权”，
 *       后者意味着 UI 上显示“先有 token 才能登录”。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    private JsonNode apiDocs() throws Exception {
        MvcResult r = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        // 注意：Spring Framework 7 移除了 MvcResult.getResponseContentAsString(Charset)，
        // 要从 getResponse() 拿。写成前者会报「找不到符号」。
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("文档端点匿名可访问，且是 OpenAPI 3.1.0")
    void apiDocsIsPublicAndIsOpenApi31() throws Exception {
        JsonNode doc = apiDocs();
        assertTrue(doc.has("openapi"), "缺少 openapi 版本字段");
        assertTrue(doc.get("openapi").asText().startsWith("3.1"),
                "期望 OpenAPI 3.1，实际 " + doc.get("openapi").asText());
        assertTrue(doc.path("info").path("title").asText().contains("DocMind"),
                "文档标题不对: " + doc.path("info").path("title").asText());
    }

    @Test
    @DisplayName("四个控制器分组都出现在文档里")
    void allControllerTagsArePresent() throws Exception {
        JsonNode doc = apiDocs();
        List<String> tags = new ArrayList<>();
        JsonNode paths = doc.path("paths");
        paths.fieldNames().forEachRemaining(p ->
                paths.get(p).fieldNames().forEachRemaining(m -> {
                    JsonNode op = paths.get(p).get(m);
                    op.path("tags").forEach(t -> tags.add(t.asText()));
                }));

        for (String expected : List.of("知识库与文档", "智能问答", "运维控制台", "认证与账号")) {
            assertTrue(tags.contains(expected),
                    "文档里缺少分组「" + expected + "」，实际有 " + tags);
        }
    }

    @Test
    @DisplayName("定义了 bearer-jwt 安全方案")
    void bearerSchemeIsDeclared() throws Exception {
        JsonNode doc = apiDocs();
        JsonNode scheme = doc.path("components").path("securitySchemes").path("bearer-jwt");
        assertFalse(scheme.isMissingNode(), "没有定义 bearer-jwt 安全方案");
        assertTrue("http".equals(scheme.path("type").asText())
                        && "bearer".equals(scheme.path("scheme").asText()),
                "安全方案类型不对: " + scheme.toString());
    }

    @Test
    @DisplayName("登录/注册不带锁；受保护接口必须带锁")
    void securityAnnotationsAreNotInverted() throws Exception {
        JsonNode doc = apiDocs();
        JsonNode paths = doc.path("paths");

        // 公开接口：不带 security
        for (String pub : List.of("/api/auth/login", "/api/auth/register")) {
            JsonNode op = paths.path(pub).path("post");
            assertFalse(op.isMissingNode(), "文档里找不到 " + pub);
            assertTrue(!op.has("security") || op.path("security").isEmpty(),
                    pub + " 是匿名接口，文档里不该带 security 要求");
        }

        // 受保护接口抽样：必须带 security
        for (String[] guarded : new String[][]{
                {"/api/kb", "get"},
                {"/api/admin/system", "get"},
                {"/api/auth/me", "get"}}) {
            JsonNode op = paths.path(guarded[0]).path(guarded[1]);
            assertFalse(op.isMissingNode(), "文档里找不到 " + guarded[0]);
            assertTrue(op.has("security") && !op.path("security").isEmpty(),
                    guarded[0] + " 需要鉴权，文档里却没标 security");
        }
    }

    @Test
    @DisplayName("文档端点在 Spring Security 过滤链下放行（未被登录态劫持）")
    void apiDocsNotBlockedBySecurityChain() throws Exception {
        // 这条走的是完整过滤链（@AutoConfigureMockMvc 会挂上 securityFilterChain）。
        // 若被拦截会拿到 401/403 而不是 200，说明 SecurityConfig 里漏了 OPENAPI_PATHS。
        MvcResult r = mvc.perform(get("/v3/api-docs")).andReturn();
        int status = r.getResponse().getStatus();
        assertTrue(status == 200,
                "/v3/api-docs 被过滤链拦住了，HTTP " + status
                        + "，检查 SecurityConfig 的 OPENAPI_PATHS 是否放行");
    }

    @Test
    @DisplayName("受保护接口仍然拒绝匿名访问（防止为开放文档而误放行全局）")
    void protectedApiStillRejectsAnonymous() throws Exception {
        mvc.perform(get("/api/kb")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/system")).andExpect(status().isUnauthorized());
    }
}
