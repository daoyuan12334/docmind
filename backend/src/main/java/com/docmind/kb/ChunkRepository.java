package com.docmind.kb;

import com.docmind.kb.KbModels.Chunk;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class ChunkRepository {

    private final JdbcClient jdbc;

    public record ChunkDraft(long docId, long kbId, int chunkIndex, String content, String contentHash,
                             int tokenCount, Integer pageNo, String contentType, String meta) {
    }

    private static final org.springframework.jdbc.core.RowMapper<Chunk> MAPPER = (rs, row) -> {
        try {
            return new Chunk(rs.getLong("id"), rs.getLong("kb_id"), rs.getLong("doc_id"), rs.getInt("chunk_index"),
                    rs.getString("content"), rs.getString("content_hash"), rs.getInt("token_count"),
                    (Integer) rs.getObject("page_no"), rs.getString("content_type"), rs.getString("meta"),
                    rs.getString("vector_id"), rs.getTimestamp("created_at") == null ? null
                    : rs.getTimestamp("created_at").toInstant());
        } catch (Exception e) {
            throw new IllegalStateException("切片映射失败", e);
        }
    };

    /** 批量写入切片并返回生成的主键，供后续写向量库时回填 vector_id。 */
    public List<Long> insertBatch(List<ChunkDraft> drafts) {
        if (drafts.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (ChunkDraft d : drafts) {
            org.springframework.jdbc.support.KeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.sql("INSERT INTO kb_chunk (kb_id, doc_id, chunk_index, content, content_hash, token_count, "
                            + "page_no, content_type, meta, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)")
                    .params(d.kbId(), d.docId(), d.chunkIndex(), d.content(), d.contentHash(), d.tokenCount(),
                            d.pageNo(), d.contentType(), d.meta(), Timestamp.from(Instant.now()))
                    .update(kh, "id");
            Number key = kh.getKey();
            ids.add(key == null ? 0L : key.longValue());
        }
        return ids;
    }

    public void updateVectorIds(List<Long> chunkIds, List<String> vectorIds) {
        if (chunkIds.size() != vectorIds.size()) {
            return;
        }
        for (int i = 0; i < chunkIds.size(); i++) {
            jdbc.sql("UPDATE kb_chunk SET vector_id = ? WHERE id = ?")
                    .params(vectorIds.get(i), chunkIds.get(i)).update();
        }
    }

    public List<Chunk> listByDoc(long docId) {
        return jdbc.sql("SELECT id, kb_id, doc_id, chunk_index, content, content_hash, token_count, page_no, "
                        + "content_type, meta, vector_id, created_at FROM kb_chunk WHERE doc_id = ? ORDER BY chunk_index")
                .param(docId).query(MAPPER).list();
    }

    public List<Chunk> listByKb(long kbId, int limit, int offset) {
        return jdbc.sql("SELECT id, kb_id, doc_id, chunk_index, content, content_hash, token_count, page_no, "
                        + "content_type, meta, vector_id, created_at FROM kb_chunk WHERE kb_id = ? "
                        + "ORDER BY id LIMIT ? OFFSET ?")
                .params(kbId, offset, limit).query(MAPPER).list();
    }

    public Map<Long, Chunk> mapByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            in.append(i == 0 ? "" : ",").append(ids.get(i));
        }
        List<Chunk> chunks = jdbc.sql("SELECT id, kb_id, doc_id, chunk_index, content, content_hash, token_count, "
                        + "page_no, content_type, meta, vector_id, created_at FROM kb_chunk WHERE id IN (" + in + ")")
                .query(MAPPER).list();
        java.util.Map<Long, Chunk> map = new java.util.LinkedHashMap<>();
        for (Chunk c : chunks) {
            map.put(c.id(), c);
        }
        return map;
    }

    public int deleteByDoc(long docId) {
        return jdbc.sql("DELETE FROM kb_chunk WHERE doc_id = ?").param(docId).update();
    }

    public int countByDoc(long docId) {
        return jdbc.sql("SELECT COUNT(*) FROM kb_chunk WHERE doc_id = ?").param(docId).query(Integer.class).single();
    }

    public int countByKb(long kbId) {
        return jdbc.sql("SELECT COUNT(*) FROM kb_chunk WHERE kb_id = ?").param(kbId).query(Integer.class).single();
    }

    public List<String> vectorIdsByDoc(long docId) {
        return jdbc.sql("SELECT vector_id FROM kb_chunk WHERE doc_id = ? AND vector_id IS NOT NULL")
                .param(docId).query(String.class).list();
    }
}
