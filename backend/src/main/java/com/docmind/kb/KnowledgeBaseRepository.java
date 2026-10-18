package com.docmind.kb;

import com.docmind.kb.KbModels.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class KnowledgeBaseRepository {

    private final JdbcClient jdbc;

    private static final String COLS = "id, name, description, owner_id, visibility, embedding_model, "
            + "chunk_size, chunk_overlap, top_k, doc_count, chunk_count, created_at, updated_at, deleted";

    public long create(String name, String description, long ownerId, String visibility,
                       String embeddingModel, int chunkSize, int chunkOverlap, int topK) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO kb_knowledge_base (name, description, owner_id, visibility, embedding_model, "
                        + "chunk_size, chunk_overlap, top_k, created_at, updated_at, deleted) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)")
                .params(name, description, ownerId, visibility, embeddingModel,
                        chunkSize, chunkOverlap, topK, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()))
                .update(holder, "id");
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    private static final org.springframework.jdbc.core.RowMapper<KnowledgeBase> MAPPER = (rs, row) -> {
        try {
            return new KnowledgeBase(
                    rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getLong("owner_id"),
                    rs.getString("visibility"), rs.getString("embedding_model"), rs.getInt("chunk_size"),
                    rs.getInt("chunk_overlap"), rs.getInt("top_k"), rs.getInt("doc_count"), rs.getInt("chunk_count"),
                    ts(rs, "created_at"), ts(rs, "updated_at"), rs.getBoolean("deleted"));
        } catch (Exception e) {
            throw new IllegalStateException("知识库映射失败", e);
        }
    };

    private static java.time.Instant ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    public Optional<KnowledgeBase> findById(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM kb_knowledge_base WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    public List<KnowledgeBase> listVisible(long userId, boolean admin) {
        if (admin) {
            return jdbc.sql("SELECT " + COLS + " FROM kb_knowledge_base WHERE deleted = FALSE ORDER BY updated_at DESC")
                    .query(MAPPER).list();
        }
        return jdbc.sql("SELECT " + COLS + " FROM kb_knowledge_base WHERE deleted = FALSE "
                        + "AND (owner_id = ? OR visibility = 'TEAM') ORDER BY updated_at DESC")
                .param(userId).query(MAPPER).list();
    }

    public void update(long id, String name, String description, String visibility, int chunkSize, int chunkOverlap, int topK) {
        jdbc.sql("UPDATE kb_knowledge_base SET name = ?, description = ?, visibility = ?, chunk_size = ?, "
                        + "chunk_overlap = ?, top_k = ?, updated_at = ? WHERE id = ?")
                .params(name, description, visibility, chunkSize, chunkOverlap, topK,
                        Timestamp.from(Instant.now()), id)
                .update();
    }

    public void softDelete(long id) {
        jdbc.sql("UPDATE kb_knowledge_base SET deleted = TRUE, updated_at = ? WHERE id = ?")
                .params(Timestamp.from(Instant.now()), id).update();
    }

    public void bumpCounts(long id, int docDelta, int chunkDelta) {
        jdbc.sql("UPDATE kb_knowledge_base SET doc_count = GREATEST(doc_count + ?, 0), "
                        + "chunk_count = GREATEST(chunk_count + ?, 0) WHERE id = ?")
                .params(docDelta, chunkDelta, id).update();
    }

    public void recount(long id) {
        Integer docs = jdbc.sql("SELECT COUNT(*) FROM kb_document WHERE kb_id = ? AND deleted = FALSE")
                .param(id).query(Integer.class).single();
        Integer chunks = jdbc.sql("SELECT COUNT(*) FROM kb_chunk WHERE kb_id = ?").param(id).query(Integer.class).single();
        jdbc.sql("UPDATE kb_knowledge_base SET doc_count = ?, chunk_count = ? WHERE id = ?")
                .params(docs == null ? 0 : docs, chunks == null ? 0 : chunks, id).update();
    }
}
