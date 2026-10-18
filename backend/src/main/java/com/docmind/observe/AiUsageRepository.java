package com.docmind.observe;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** AI 调用流水：用于成本分摊、异常排查与用量看板。 */
@Repository
@RequiredArgsConstructor
public class AiUsageRepository {

    private final JdbcClient jdbc;

    public void record(Long userId, Long kbId, Long conversationId, String scene, String model,
                       int promptTokens, int completionTokens, int latencyMs, boolean success) {
        jdbc.sql("INSERT INTO ai_usage (user_id, kb_id, conversation_id, scene, model, prompt_tokens, "
                        + "completion_tokens, latency_ms, success, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)")
                .params(userId, kbId, conversationId, scene, model, promptTokens, completionTokens, latencyMs,
                        success, Timestamp.from(Instant.now()))
                .update();
    }

    public long sumTokensToday(long userId) {
        Long total = jdbc.sql("SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM ai_usage "
                        + "WHERE user_id = ? AND created_at >= ?")
                .params(userId, Timestamp.from(Instant.now().minusSeconds(86400)))
                .query(Long.class)
                .single();
        return total == null ? 0L : total;
    }

    public Stats stats(int hours) {
        var row = jdbc.sql("SELECT COUNT(*) AS c, COALESCE(SUM(prompt_tokens),0) AS p, "
                        + "COALESCE(SUM(completion_tokens),0) AS q, COALESCE(AVG(latency_ms),0) AS l "
                        + "FROM ai_usage WHERE created_at >= ?")
                .param(Timestamp.from(Instant.now().minusSeconds(hours * 3600L)))
                .query((rs, r) -> new Stats(rs.getLong("c"), rs.getLong("p"), rs.getLong("q"), rs.getLong("l")))
                .optional();
        return row.orElse(new Stats(0, 0, 0, 0));
    }

    public record Stats(long calls, long promptTokens, long completionTokens, long avgLatencyMs) {
    }
}
