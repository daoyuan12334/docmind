package com.docmind.admin;

import com.docmind.auth.UserRepository;
import com.docmind.common.ApiResult;
import com.docmind.config.AppProperties;
import com.docmind.infra.bus.MessageBus;
import com.docmind.infra.cache.CachePort;
import com.docmind.infra.storage.StoragePort;
import com.docmind.ingest.IngestTaskRepository;
import com.docmind.kb.DocumentRepository;
import com.docmind.kb.KnowledgeBaseRepository;
import com.docmind.observe.AiUsageRepository;
import com.docmind.observe.AuditService;
import com.docmind.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 运维控制台：概览、用量、审计、链路组件状态。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;
    private final IngestTaskRepository tasks;
    private final AiUsageRepository usage;
    private final AuditService audit;
    private final CachePort cache;
    private final MessageBus bus;
    private final StoragePort storage;
    private final AppProperties props;
    private final Environment environment;

    @GetMapping("/overview")
    public ApiResult<?> overview() {
        long userCount = users.count();
        long kbCount = count("SELECT COUNT(*) FROM kb_knowledge_base WHERE deleted = FALSE");
        long docCount = count("SELECT COUNT(*) FROM kb_document WHERE deleted = FALSE");
        long chunkCount = count("SELECT COUNT(*) FROM kb_chunk");
        long indexed = count("SELECT COUNT(*) FROM kb_document WHERE status = 'INDEXED' AND deleted = FALSE");
        long failed = count("SELECT COUNT(*) FROM kb_document WHERE status = 'FAILED' AND deleted = FALSE");
        long running = tasks.countRunning();
        audit.record("VIEW_OVERVIEW", "admin", null);
        return ApiResult.ok(Map.of(
                "users", userCount,
                "knowledgeBases", kbCount,
                "documents", docCount,
                "indexed", indexed,
                "failed", failed,
                "chunks", chunkCount,
                "runningTasks", running));
    }

    @GetMapping("/usage")
    public ApiResult<?> usage(@RequestParam(defaultValue = "24") int hours) {
        return ApiResult.ok(this.usage.stats(hours));
    }

    @GetMapping("/audit")
    public ApiResult<?> audit(@RequestParam(defaultValue = "50") int limit) {
        var rows = jdbc.sql("SELECT id, user_id, username, action, resource, ip, created_at FROM audit_log "
                        + "ORDER BY id DESC LIMIT ?")
                .param(limit)
                .query((rs, row) -> Map.of(
                        "id", rs.getLong("id"),
                        "username", rs.getString("username") == null ? "" : rs.getString("username"),
                        "action", rs.getString("action"),
                        "resource", rs.getString("resource") == null ? "" : rs.getString("resource"),
                        "ip", rs.getString("ip") == null ? "" : rs.getString("ip"),
                        "createdAt", rs.getTimestamp("created_at").toInstant().toString()))
                .list();
        return ApiResult.ok(rows);
    }

    /** 组件拓扑：一眼看清当前跑的是哪套中间件实现。 */
    @GetMapping("/system")
    public ApiResult<?> system() {
        return ApiResult.ok(Map.of(
                "profile", String.join(",", environment.getActiveProfiles()),
                "messageBus", bus.type(),
                "cache", cache.getClass().getSimpleName(),
                "cacheAlive", cache.ping(),
                "storage", storage.type(),
                "chatModel", props.getAi().getChatModel(),
                "embeddingModel", props.getAi().getEmbeddingModel(),
                "visionModel", props.getMultimodal().getVisionModel(),
                "omniModel", props.getMultimodal().getOmniModel(),
                "mcpExposedKbIds", props.getMcp().getExposeKbIds()));
    }

    @GetMapping("/quota")
    public ApiResult<?> quota() {
        long used = usage.sumTokensToday(CurrentUser.id());
        return ApiResult.ok(Map.<String, Object>of(
                "usedToday", used,
                "limit", props.getQuota().getDailyTokensPerUser(),
                "remaining", Math.max(0, props.getQuota().getDailyTokensPerUser() - used),
                "requestsPerMinute", props.getQuota().getRequestsPerMinute()));
    }

    private long count(String sql) {
        Long v = jdbc.sql(sql).query(Long.class).single();
        return v == null ? 0L : v;
    }
}
