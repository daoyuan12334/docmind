package com.docmind.ingest;

import com.docmind.kb.ChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量索引服务。底层是 Spring AI 的 VectorStore（prod: pgvector / dev: 内存），
 * 这里统一封装「切片 → 向量文档 → 入库 → 回写 vector_id」的流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorIndexer {

    public static final String META_KB_ID = "kbId";
    public static final String META_DOC_ID = "docId";
    public static final String META_CHUNK_ID = "chunkId";
    public static final String META_DOC_NAME = "docName";
    public static final String META_CONTENT_TYPE = "contentType";

    private final VectorStore vectorStore;

    /** 写入向量并返回生成的向量 ID 列表（顺序与 drafts 一致）。 */
    public List<String> index(long kbId, long docId, String docName,
                              List<ChunkRepository.ChunkDraft> drafts, List<Long> chunkIds) {
        if (drafts.isEmpty()) {
            return List.of();
        }
        List<Document> docs = new ArrayList<>(drafts.size());
        List<String> ids = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            var d = drafts.get(i);
            String vectorId = UUID.randomUUID().toString();
            ids.add(vectorId);
            docs.add(Document.builder()
                    .id(vectorId)
                    .text(d.content())
                    .metadata(Map.of(
                            META_KB_ID, kbId,
                            META_DOC_ID, docId,
                            META_CHUNK_ID, chunkIds.get(i),
                            META_DOC_NAME, docName,
                            META_CONTENT_TYPE, d.contentType()))
                    .build());
        }
        vectorStore.add(docs);
        return ids;
    }

    public void deleteByIds(List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return;
        }
        vectorStore.delete(vectorIds);
    }

    public void deleteByKb(long kbId) {
        vectorStore.delete(new FilterExpressionBuilder().eq(META_KB_ID, kbId).build());
    }

    public void deleteByDoc(long docId) {
        vectorStore.delete(new FilterExpressionBuilder().eq(META_DOC_ID, docId).build());
    }

    /** 检索：按知识库隔离，返回候选集（融合前的向量通道）。 */
    public List<Document> search(long kbId, String query, int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .filterExpression(new FilterExpressionBuilder().eq(META_KB_ID, kbId).build())
                .build());
    }
}
