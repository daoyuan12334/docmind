package com.docmind.mcp;

import com.docmind.config.AppProperties;
import com.docmind.kb.DocumentRepository;
import com.docmind.kb.KbModels;
import com.docmind.kb.KnowledgeBaseRepository;
import com.docmind.observe.MetricsService;
import com.docmind.rag.RagModels;
import com.docmind.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 通过 MCP 暴露知识库能力，让外部 Agent（Claude Desktop、Cursor、自研编排）直接调用。
 *
 * <p>安全边界：只有配置在 docmind.mcp.expose-kb-ids 里的知识库可被访问，
 * 未配置时默认全部拒绝，避免私有资料被 MCP 侧信道读取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeMcpTools {

    private final RagService ragService;
    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;
    private final AppProperties props;
    private final MetricsService metrics;

    @McpTool(name = "list_knowledge_bases", description = "列出当前 MCP 服务允许访问的知识库及其文档数量")
    public String listKnowledgeBases() {
        Set<Long> allowed = allowedKbIds();
        if (allowed.isEmpty()) {
            return "未配置任何可访问的知识库（docmind.mcp.expose-kb-ids）";
        }
        List<String> lines = new java.util.ArrayList<>();
        for (Long id : allowed) {
            knowledgeBases.findById(id).ifPresent(kb -> lines.add(
                    "id=%d | 名称=%s | 文档数=%d | 切片数=%d".formatted(kb.id(), kb.name(), kb.docCount(), kb.chunkCount())));
        }
        metrics.recordMcpCall("listKnowledgeBases", true);
        return String.join("\n", lines);
    }

    @McpTool(name = "search_knowledge_base", description = "在指定知识库中做混合检索，返回最相关的原文片段及来源")
    public String searchKnowledgeBase(
            @McpToolParam(description = "检索关键词或问题") String query,
            @McpToolParam(description = "知识库 ID，可通过 listKnowledgeBases 获取") long kbId,
            @McpToolParam(description = "返回条数，默认 5") Integer topK) {
        requireAllowed(kbId);
        int k = topK == null || topK <= 0 ? 5 : Math.min(topK, 20);
        List<RagModels.RetrievedChunk> chunks = ragService.retrieve(kbId, query, k);
        metrics.recordMcpCall("searchKnowledgeBase", true);
        if (chunks.isEmpty()) {
            return "未检索到相关内容";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append(c.docName())
                    .append(c.pageNo() == null ? "" : " · 第" + c.pageNo() + "页")
                    .append(" · 相关度 ").append(String.format("%.3f", c.score()))
                    .append('\n')
                    .append(c.content())
                    .append("\n\n");
        }
        return sb.toString();
    }

    @McpTool(name = "ask_knowledge_base", description = "基于知识库内容回答问题，返回答案与引用来源")
    public String askKnowledgeBase(
            @McpToolParam(description = "用户问题") String question,
            @McpToolParam(description = "知识库 ID") long kbId) {
        requireAllowed(kbId);
        RagModels.RagAnswer answer = ragService.answer(kbId, question, List.of(), null, null, null);
        metrics.recordMcpCall("askKnowledgeBase", true);
        StringBuilder sb = new StringBuilder(answer.answer());
        if (!answer.citations().isEmpty()) {
            sb.append("\n\n来源：\n");
            for (RagModels.Citation c : answer.citations()) {
                sb.append("[").append(c.index()).append("] ")
                        .append(c.docName())
                        .append(c.pageNo() == null ? "" : " · 第" + c.pageNo() + "页")
                        .append('\n');
            }
        }
        return sb.toString();
    }

    @McpTool(name = "get_document_summary", description = "查看某个文档的摘要与处理状态")
    public String getDocumentSummary(@McpToolParam(description = "文档 ID") long documentId) {
        KbModels.Document doc = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + documentId));
        requireAllowed(doc.kbId());
        metrics.recordMcpCall("getDocumentSummary", true);
        return "文档：%s\n类型：%s\n状态：%s\n页数：%d\n切片数：%d\n摘要：%s".formatted(
                doc.name(), doc.modality(), doc.status(), doc.pageCount(), doc.chunkCount(),
                doc.summary() == null ? "（无）" : doc.summary());
    }

    private void requireAllowed(long kbId) {
        Set<Long> allowed = allowedKbIds();
        if (allowed.isEmpty() || !allowed.contains(kbId)) {
            log.warn("MCP 访问被拒绝 kbId={} allowed={}", kbId, allowed);
            throw new IllegalArgumentException("知识库 " + kbId + " 未授权给 MCP 访问");
        }
    }

    private Set<Long> allowedKbIds() {
        String raw = props.getMcp().getExposeKbIds();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<Long> ids = new java.util.LinkedHashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }
}
