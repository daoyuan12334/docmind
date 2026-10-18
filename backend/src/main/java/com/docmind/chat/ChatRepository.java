package com.docmind.chat;

import com.docmind.common.BizException;
import com.docmind.ingest.multimodal.MultimodalService;
import com.docmind.kb.KbAccess;
import com.docmind.rag.RagModels;
import com.docmind.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 会话与消息持久化。消息表同时记录 token 与耗时，作为成本分析的原始依据。 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ChatRepository {

    private final JdbcClient jdbc;

    public long createConversation(long userId, Long kbId, String title) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO chat_conversation (kb_id, user_id, title, message_count, created_at, updated_at, deleted) "
                        + "VALUES (?, ?, ?, 0, ?, ?, FALSE)")
                .params(kbId, userId, title, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()))
                .update(holder, "id");
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public List<Map<String, Object>> listConversations(long userId, int limit) {
        return jdbc.sql("SELECT id, kb_id, title, message_count, created_at, updated_at FROM chat_conversation "
                        + "WHERE user_id = ? AND deleted = FALSE ORDER BY updated_at DESC LIMIT ?")
                .params(userId, limit)
                .query((rs, row) -> Map.of(
                        "id", rs.getLong("id"),
                        "kbId", rs.getObject("kb_id"),
                        "title", rs.getString("title") == null ? "" : rs.getString("title"),
                        "messageCount", rs.getInt("message_count"),
                        "updatedAt", rs.getTimestamp("updated_at").toInstant().toString()))
                .list();
    }

    public void deleteConversation(long userId, long conversationId) {
        jdbc.sql("UPDATE chat_conversation SET deleted = TRUE WHERE id = ? AND user_id = ?")
                .params(conversationId, userId).update();
    }

    public long addMessage(long conversationId, String role, String content, String citations,
                           String attachments, int promptTokens, int completionTokens, int latencyMs, String model) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO chat_message (conversation_id, role, content, citations, attachments, prompt_tokens, "
                        + "completion_tokens, latency_ms, model, trace_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")
                .params(conversationId, role, content, citations, attachments, promptTokens, completionTokens,
                        latencyMs, model, com.docmind.common.TraceContext.current(), Timestamp.from(Instant.now()))
                .update(holder, "id");
        jdbc.sql("UPDATE chat_conversation SET message_count = message_count + 1, updated_at = ? WHERE id = ?")
                .params(Timestamp.from(Instant.now()), conversationId).update();
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public List<Map<String, Object>> listMessages(long conversationId, int limit) {
        return jdbc.sql("SELECT role, content, citations, attachments, latency_ms, model, created_at FROM chat_message "
                        + "WHERE conversation_id = ? ORDER BY id DESC LIMIT ?")
                .params(conversationId, limit)
                .query((rs, row) -> Map.<String, Object>of(
                        "role", rs.getString("role"),
                        "content", rs.getString("content") == null ? "" : rs.getString("content"),
                        "citations", rs.getString("citations") == null ? "" : rs.getString("citations"),
                        "createdAt", rs.getTimestamp("created_at").toInstant().toString()))
                .list();
    }

    public void ensureOwner(long userId, long conversationId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM chat_conversation WHERE id = ? AND user_id = ?")
                .params(conversationId, userId).query(Integer.class).single();
        if (count == null || count == 0) {
            throw BizException.notFound("会话");
        }
    }
}
