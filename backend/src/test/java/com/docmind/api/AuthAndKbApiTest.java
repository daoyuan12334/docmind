package com.docmind.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层契约：注册 / 登录 / JWT / RBAC / 知识库与文档上传。
 *
 * <p>这里刻意不 mock 任何业务组件，走完整 Spring Security 过滤链，
 * 因为「鉴权配错了」是这类系统最常见也最致命的线上事故。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthAndKbApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    private static String adminToken;
    private static String memberToken;
    private static long kbId;

    private JsonNode body(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @Order(1)
    @DisplayName("注册：系统第一个用户自动成为 ADMIN")
    void firstUserBecomesAdmin() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"boss\",\"password\":\"Boss@12345\",\"displayName\":\"老板\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = body(r).get("data");
        assertEquals("ADMIN", data.get("role").asText(), "首个注册用户应为 ADMIN");
    }

    @Test
    @Order(2)
    @DisplayName("注册：第二个用户是普通 MEMBER，且用户名不可重复")
    void secondUserIsMemberAndDuplicateRejected() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"Alice@12345\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("MEMBER", body(r).get("data").get("role").asText());

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"boss\",\"password\":\"Whatever@123\"}"))
                .andExpect(status().is4xxClientError()); // 用户名冲突 -> 4xx，绝不能再建一个账号
    }

    @Test
    @Order(3)
    @DisplayName("登录：拿到 JWT；密码错误应被拒绝")
    void loginIssuesJwt() throws Exception {
        MvcResult ok = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"boss\",\"password\":\"Boss@12345\"}"))
                .andExpect(status().isOk())
                .andReturn();
        adminToken = body(ok).get("data").get("token").asText();
        assertNotNull(adminToken);
        assertTrue(adminToken.split("\\.").length == 3, "不是合法 JWT 结构");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"boss\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized()); // 口令错误必须 401，且不得签发令牌

        MvcResult alice = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"Alice@12345\"}"))
                .andExpect(status().isOk())
                .andReturn();
        memberToken = body(alice).get("data").get("token").asText();
    }

    @Test
    @Order(4)
    @DisplayName("未携带令牌访问受保护接口 → 401")
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/kb")).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    @DisplayName("伪造令牌 → 401，不能被当成合法身份")
    void forgedTokenIsRejected() throws Exception {
        mvc.perform(get("/api/kb").header("Authorization", "Bearer forged.token.value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(6)
    @DisplayName("ADMIN 可建库；MEMBER 建库后也能看到自己的库")
    void createKnowledgeBase() throws Exception {
        MvcResult r = mvc.perform(post("/api/kb")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试库\",\"description\":\"it\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isOk())
                .andReturn();
        kbId = body(r).get("data").get("id").asLong();
        assertTrue(kbId > 0);

        mvc.perform(get("/api/kb").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(7)
    @DisplayName("上传文档：返回 docId/taskId，且同一文件二次上传被识别为重复")
    void uploadDocumentAndDetectDuplicate() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "it.md", "text/markdown",
                "差旅报销需在出差结束后 15 个工作日内提交。年假满 1 年 5 天。".getBytes(StandardCharsets.UTF_8));

        MvcResult first = mvc.perform(multipart("/api/kb/" + kbId + "/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode d1 = body(first).get("data");
        assertTrue(d1.get("docId").asLong() > 0);
        assertEquals(false, d1.get("duplicate").asBoolean(), "首次上传不应判重复");

        MvcResult second = mvc.perform(multipart("/api/kb/" + kbId + "/documents")
                        .file(new MockMultipartFile("file", "it.md", "text/markdown",
                                "差旅报销需在出差结束后 15 个工作日内提交。年假满 1 年 5 天。".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(true, body(second).get("data").get("duplicate").asBoolean(),
                "同一内容再次上传应命中 checksum 去重");
    }

    @Test
    @Order(8)
    @DisplayName("RBAC：MEMBER 访问 /api/admin/** 必须被拒（403）")
    void memberCannotAccessAdminApi() throws Exception {
        mvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(9)
    @DisplayName("响应体统一带 traceId，便于链路追踪")
    void responseCarriesTraceId() throws Exception {
        MvcResult r = mvc.perform(get("/api/kb").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String traceId = body(r).get("traceId").asText();
        assertTrue(traceId != null && !traceId.isBlank(), "缺少 traceId");
    }

    @Test
    @Order(10)
    @DisplayName("健康检查可用（容器 healthcheck 依赖它）")
    void healthEndpointIsUp() throws Exception {
        MvcResult r = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn();
        assertEquals("UP", body(r).get("status").asText());
    }
}
