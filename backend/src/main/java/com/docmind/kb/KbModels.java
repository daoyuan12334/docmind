package com.docmind.kb;

import java.time.Instant;

/** 知识库域模型。用不可变 record，避免实体在 Service 间被意外修改。 */
public final class KbModels {

    private KbModels() {
    }

    public record KnowledgeBase(Long id, String name, String description, Long ownerId, String visibility,
                                String embeddingModel, int chunkSize, int chunkOverlap, int topK,
                                int docCount, int chunkCount, Instant createdAt, Instant updatedAt, boolean deleted) {
        public boolean visibleTo(long userId, boolean admin) {
            return admin || ownerId.equals(userId) || "TEAM".equalsIgnoreCase(visibility);
        }
    }

    /** 文档处理状态机：PENDING → PARSING → CHUNKING → EMBEDDING → INDEXED，失败落 FAILED。 */
    public enum DocStatus {
        PENDING, PARSING, CHUNKING, EMBEDDING, INDEXED, FAILED, DELETING
    }

    /** 内容模态：决定走哪条解析流水线。 */
    public enum Modality {
        TEXT, IMAGE, SHEET, SLIDE, AUDIO, VIDEO, PDF
    }

    public record Document(Long id, Long kbId, String name, String ext, String mimeType, long sizeBytes,
                           String checksum, String storageKey, DocStatus status, String stage, int progress,
                           String errorMsg, int pageCount, int chunkCount, int tokenCount,
                           Modality modality, String summary, String meta, Long uploadedBy,
                           Instant createdAt, Instant updatedAt, boolean deleted) {
    }

    public record Chunk(Long id, Long kbId, Long docId, int chunkIndex, String content, String contentHash,
                        int tokenCount, Integer pageNo, String contentType, String meta, String vectorId,
                        Instant createdAt) {
    }
}
