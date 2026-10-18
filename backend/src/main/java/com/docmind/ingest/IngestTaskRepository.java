package com.docmind.ingest;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 摄取任务表：记录每次流水线执行的阶段、耗时与失败原因，供运维排查与队列可视化。 */
@Repository
@RequiredArgsConstructor
public class IngestTaskRepository {

    private final JdbcClient jdbc;

    public long create(long docId, long kbId, String traceId) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO ingest_task (doc_id, kb_id, trace_id, status, stage, attempt, created_at) "
                        + "VALUES (?, ?, ?, 'RUNNING', 'QUEUED', 1, ?)")
                .params(docId, kbId, traceId, Timestamp.from(Instant.now()))
                .update(holder, "id");
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void start(long taskId, int attempt) {
        jdbc.sql("UPDATE ingest_task SET status = 'RUNNING', attempt = ?, started_at = ? WHERE id = ?")
                .params(attempt, Timestamp.from(Instant.now()), taskId).update();
    }

    public void stage(long taskId, String stage) {
        jdbc.sql("UPDATE ingest_task SET stage = ? WHERE id = ?").params(stage, taskId).update();
    }

    public void success(long taskId, long costMs) {
        jdbc.sql("UPDATE ingest_task SET status = 'SUCCESS', stage = 'DONE', cost_ms = ?, finished_at = ? WHERE id = ?")
                .params(costMs, Timestamp.from(Instant.now()), taskId).update();
    }

    public void retrying(long taskId, String error, long costMs) {
        jdbc.sql("UPDATE ingest_task SET status = 'RETRYING', error = ?, cost_ms = ?, finished_at = ? WHERE id = ?")
                .params(clip(error, 1900), costMs, Timestamp.from(Instant.now()), taskId).update();
    }

    public void dead(long taskId, String error, long costMs) {
        jdbc.sql("UPDATE ingest_task SET status = 'DEAD', error = ?, cost_ms = ?, finished_at = ? WHERE id = ?")
                .params(clip(error, 1900), costMs, Timestamp.from(Instant.now()), taskId).update();
    }

    public List<TaskView> listByDoc(long docId, int limit) {
        return jdbc.sql("SELECT id, doc_id, status, stage, attempt, error, cost_ms, started_at, finished_at, created_at "
                        + "FROM ingest_task WHERE doc_id = ? ORDER BY id DESC LIMIT ?")
                .params(docId, limit)
                .query((rs, row) -> new TaskView(
                        rs.getLong("id"), rs.getLong("doc_id"), rs.getString("status"), rs.getString("stage"),
                        rs.getInt("attempt"), rs.getString("error"), rs.getLong("cost_ms"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public long countRunning() {
        return jdbc.sql("SELECT COUNT(*) FROM ingest_task WHERE status = 'RUNNING'").query(Long.class).single();
    }

    public record TaskView(long id, long docId, String status, String stage, int attempt, String error,
                           long costMs, Instant createdAt) {
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
