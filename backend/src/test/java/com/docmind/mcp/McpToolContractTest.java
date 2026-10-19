package com.docmind.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具「契约」回归：生成的 JSON Schema 必须与方法实现对得上。
 *
 * <p>背景（真实踩过的坑）：{@code search_knowledge_base} 的 {@code topK} 参数，
 * 方法体里明确写了 {@code topK == null || topK <= 0 ? 5 : Math.min(topK, 20)}
 * —— 也就是允许客户端省略。但 {@link McpToolParam#required()} 的默认值是
 * {@code true}，注解上没显式写 {@code required = false}，
 * 于是生成的 Schema 把它列进 required，客户端一省略就被框架挡掉：
 * <pre>
 *   Tool (search_knowledge_base) input validation failed:
 *     Validation failed: JSON schema validation errors: [: 未找到所需属性"topK"]
 * </pre>
 *
 * <p>这类缺陷的隐蔽之处在于：失败发生在框架校验层，业务代码根本没执行到，
 * 日志里干干净净，排查时很容易一直在错误的层面上找原因。
 */
class McpToolContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("可选参数 topK 不得出现在 Schema 的 required 中")
    void optionalParamIsNotRequired() throws Exception {
        Method m = KnowledgeMcpTools.class.getMethod(
                "searchKnowledgeBase", String.class, long.class, Integer.class);

        JsonNode schema = MAPPER.readTree(McpJsonSchemaGenerator.generateForMethodInput(m));
        List<String> required = requiredNames(schema);

        // 实现支持省略 topK（null → 默认 5），契约就必须也是可选
        assertFalse(required.contains("topK"),
                "topK 在实现里是可选的（null 时取默认 5），Schema 却把它列为必填，"
                        + "会导致客户端省略该参数时直接被框架校验拒绝。需要在 @McpToolParam 上写 required = false。");
    }

    @Test
    @DisplayName("真正必填的参数 query / kbId 仍必须在 required 中")
    void requiredParamsAreStillDeclared() throws Exception {
        Method m = KnowledgeMcpTools.class.getMethod(
                "searchKnowledgeBase", String.class, long.class, Integer.class);

        List<String> required = requiredNames(MAPPER.readTree(
                McpJsonSchemaGenerator.generateForMethodInput(m)));

        assertTrue(required.contains("query"), "query 是必填参数，必须出现在 required 中");
        assertTrue(required.contains("kbId"), "kbId 是必填参数，必须出现在 required 中");
    }

    @Test
    @DisplayName("所有 @McpTool 方法都必须带非空 description")
    void everyToolHasDescription() {
        List<String> nameless = new ArrayList<>();
        for (Method m : KnowledgeMcpTools.class.getDeclaredMethods()) {
            McpTool ann = m.getAnnotation(McpTool.class);
            if (ann == null) {
                continue;
            }
            if (ann.name() == null || ann.name().isBlank()
                    || ann.description() == null || ann.description().isBlank()) {
                nameless.add(m.getName());
            }
        }
        assertTrue(nameless.isEmpty(),
                "以下 @McpTool 方法缺少 name 或 description，MCP 客户端将无法正确使用："
                        + String.join(", ", nameless));
    }

    @Test
    @DisplayName("标注为 required=false 的参数，其 Java 类型必须是可空的（不能是基本类型）")
    void optionalParamsMustBeNullableTypes() {
        List<String> bad = new ArrayList<>();
        for (Method m : KnowledgeMcpTools.class.getDeclaredMethods()) {
            if (m.getAnnotation(McpTool.class) == null) {
                continue;
            }
            for (Parameter p : m.getParameters()) {
                McpToolParam ann = p.getAnnotation(McpToolParam.class);
                if (ann != null && !ann.required() && p.getType().isPrimitive()) {
                    bad.add(m.getName() + "." + p.getName() + ":" + p.getType().getSimpleName());
                }
            }
        }
        assertTrue(bad.isEmpty(),
                "以下参数标了 required=false 但用了基本类型，反序列化时 null 会直接抛异常："
                        + String.join(", ", bad));
    }

    private static List<String> requiredNames(JsonNode schema) {
        JsonNode req = schema.get("required");
        if (req == null || !req.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        req.forEach(n -> out.add(n.asText()));
        return out;
    }
}
