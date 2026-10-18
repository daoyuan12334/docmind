package com.docmind.kb;

import com.docmind.kb.KbModels.DocStatus;
import com.docmind.kb.KbModels.Document;
import com.docmind.kb.KbModels.Modality;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DocumentRepository {

    private final JdbcClient jdbc;

    private static final String COLS = "id, kb_id, name, ext, mime_type, size_bytes, checksum, storage_key, status, "
            + "stage, progress, error_msg, page_count, chunk_count, token_count, modality, summary, meta, "
            + "uploaded_by, created_at, updated_at, deleted";

    private static final org.springframework.jdbc.core.RowMapper<Document> MAPPER = (rs, row) -> {
        try {
            return new Document(
                    rs.getLong("id"), rs.getLong("kb_id"), rs.getString("name"), rs.getString("ext"),
                    rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("checksum"),
                    rs.getString("storage_key"), DocStatus.valueOf(rs.getString("status")), rs.getString("stage"),
                    rs.getInt("progress"), rs.getString("error_msg"), rs.getInt("page_count"),
                    rs.getInt("chunk_count"), rs.getInt("token_count"),
                    Modality.valueOf(rs.getString("modality")), rs.getString("summary"), rs.getString("meta"),
                    (Long) rs.getObject("uploaded_by"), ts(rs, "created_at"), ts(rs, "updated_at"),
                    rs.getBoolean("deleted"));
        } catch (Exception e) {
            throw new IllegalStateException("文档映射失败", e);
        }
    };

    private static Instant ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    public long create(long kbId, String name, String ext, String mimeType, long sizeBytes, String checksum,
                       String storageKey, Modality modality, long uploadedBy) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO kb_document (kb_id, name, ext, mime_type, size_bytes, checksum, storage_key, "
                        + "status, stage, progress, chunk_count, token_count, page_count, modality, uploaded_by, "
                        + "created_at, updated_at, deleted) VALUES (?,?,?,?,?,?,?,'PENDING','QUEUED',0,0,0,0,?,?,?,?,FALSE)")
                .params(kbId, name, ext, mimeType, sizeBytes, checksum, storageKey,
                        modality.name(), uploadedBy, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()))
                .update(holder, "id");
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public Optional<Document> findById(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM kb_document WHERE id = ?").param(id).query(MAPPER).optional();
    }

    /**
     * 按内容指纹查重。
     *
     * <p>必须排除 FAILED：失败的副本会残留（用户很可能修好后重传同名同内容文件），
     * 让它参与去重既会挡住重传，也会在「同一个文件失败过两次」时查到 2 行，
     * 直接让 {@code optional()} 抛 IncorrectResultSizeDataAccessException。
     * ORDER BY id LIMIT 1 保证命中最新/唯一的一条，不再依赖返回行数。
     */
    public Optional<Document> findByChecksum(long kbId, String checksum) {
        return jdbc.sql("SELECT " + COLS + " FROM kb_document "
                        + "WHERE kb_id = ? AND checksum = ? AND deleted = FALSE AND status <> 'FAILED' "
                        + "ORDER BY id LIMIT 1")
                .params(kbId, checksum).query(MAPPER).optional();
    }

    public List<Document> listByKb(long kbId) {
        return jdbc.sql("SELECT " + COLS + " FROM kb_document WHERE kb_id = ? AND deleted = FALSE ORDER BY id DESC")
                .param(kbId).query(MAPPER).list();
    }

    /** 批量取文档名，供检索结果渲染来源。 */
    public java.util.Map<Long, String> namesByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        StringBuilder in = new StringBuilder();
        for (Long id : ids) {
            if (in.length() > 0) {
                in.append(',');
            }
            in.append(id);
        }
        Map<Long, String> map = new HashMap<>();
        jdbc.sql("SELECT id, name FROM kb_document WHERE id IN (" + in + ")")
                .query((rs, row) -> new String[]{String.valueOf(rs.getLong("id")), rs.getString("name")})
                .list()
                .forEach(pair -> map.put(Long.valueOf(pair[0]), pair[1]));
        return map;
    }

    public int countIndexed(long kbId) {
        return jdbc.sql("SELECT COUNT(*) FROM kb_document WHERE kb_id = ? AND status = 'INDEXED' AND deleted = FALSE")
                .param(kbId).query(Integer.class).single();
    }

    public void updateStatus(long id, DocStatus status, String stage, int progress, String error) {
        jdbc.sql("UPDATE kb_document SET status = ?, stage = ?, progress = ?, error_msg = ?, updated_at = ? WHERE id = ?")
                .params(status.name(), stage, progress, error, Timestamp.from(Instant.now()), id).update();
    }

    public void updateProgress(long id, String stage, int progress) {
        jdbc.sql("UPDATE kb_document SET stage = ?, progress = ?, updated_at = ? WHERE id = ?")
                .params(stage, progress, Timestamp.from(Instant.now()), id).update();
    }

    public void finishSuccess(long id, int pageCount, int chunkCount, int tokenCount, String summary, Modality modality) {
        jdbc.sql("UPDATE kb_document SET status = 'INDEXED', stage = 'DONE', progress = 100, page_count = ?, "
                        + "chunk_count = ?, token_count = ?, summary = ?, modality = ?, error_msg = NULL, updated_at = ? WHERE id = ?")
                .params(pageCount, chunkCount, tokenCount, summary, modality.name(),
                        Timestamp.from(Instant.now()), id).update();
    }

    public void markFailed(long id, String error) {
        jdbc.sql("UPDATE kb_document SET status = 'FAILED', stage = 'FAILED', error_msg = ?, updated_at = ? WHERE id = ?")
                .params(clip(error, 1000), Timestamp.from(Instant.now()), id).update();
    }

    public void softDelete(long id) {
        jdbc.sql("UPDATE kb_document SET deleted = TRUE, updated_at = ? WHERE id = ?")
                .params(Timestamp.from(Instant.now()), id).update();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
