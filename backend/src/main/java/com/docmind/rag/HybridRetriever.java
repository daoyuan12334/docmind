package com.docmind.rag;

import com.docmind.config.AppProperties;
import com.docmind.ingest.VectorIndexer;
import com.docmind.kb.ChunkRepository;
import com.docmind.kb.DocumentRepository;
import com.docmind.kb.KbModels;
import com.docmind.observe.MetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索：向量召回 + 关键词召回，用 RRF（Reciprocal Rank Fusion）融合。
 * RRF 的好处是不需要归一化两路分数，鲁棒且实现简单：
 * score = Σ w / (k + rank)，k 常取 60。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetriever {

    private static final int RRF_K = 60;

    private final VectorIndexer vectorIndexer;
    private final KeywordSearchPort keywordSearch;
    private final ChunkRepository chunks;
    private final DocumentRepository documents;
    private final AppProperties props;
    private final MetricsService metrics;

    public List<RagModels.RetrievedChunk> retrieve(long kbId, String query, int topK) {
        long start = System.currentTimeMillis();
        int candidateK = Math.max(topK, props.getRag().getCandidateK());

        List<Document> vectorDocs = vectorIndexer.search(kbId, query, candidateK);
        List<KeywordSearchPort.Hit> keywordHits = keywordSearch.search(kbId, query, candidateK);

        // 1) 向量通道打分（Spring AI 返回的 score 为相似度，越大越相关）
        Map<Long, Double> vectorScores = new HashMap<>();
        Map<Long, String> vectorIds = new HashMap<>();
        for (int i = 0; i < vectorDocs.size(); i++) {
            Document d = vectorDocs.get(i);
            Long chunkId = longValue(d.getMetadata().get(VectorIndexer.META_CHUNK_ID));
            if (chunkId == null) {
                continue;
            }
            double rankScore = props.getRag().getVectorWeight() * (1.0 / (RRF_K + i));
            double sim = d.getScore() == null ? rankScore : Math.max(rankScore, d.getScore());
            vectorScores.put(chunkId, vectorScores.getOrDefault(chunkId, 0.0) + sim);
            vectorIds.put(chunkId, d.getId());
        }

        // 2) 关键词通道打分
        Map<Long, Double> keywordScores = new HashMap<>();
        for (int i = 0; i < keywordHits.size(); i++) {
            KeywordSearchPort.Hit hit = keywordHits.get(i);
            double rankScore = props.getRag().getKeywordWeight() * (1.0 / (RRF_K + i));
            keywordScores.put(hit.chunkId(), keywordScores.getOrDefault(hit.chunkId(), 0.0) + rankScore);
        }

        // 3) 融合
        Set<Long> union = new HashSet<>(vectorScores.keySet());
        union.addAll(keywordScores.keySet());
        List<Long> ids = new ArrayList<>(union);
        Map<Long, KbModels.Chunk> chunkMap = chunks.mapByIds(ids);
        Set<Long> docIds = new HashSet<>();
        for (KbModels.Chunk c : chunkMap.values()) {
            docIds.add(c.docId());
        }
        Map<Long, String> docNames = documents.namesByIds(docIds);

        List<RagModels.RetrievedChunk> result = new ArrayList<>();
        for (Long id : ids) {
            KbModels.Chunk c = chunkMap.get(id);
            if (c == null) {
                continue;
            }
            double v = vectorScores.getOrDefault(id, 0.0);
            double k = keywordScores.getOrDefault(id, 0.0);
            double score = v + k;
            if (score < props.getRag().getMinScore()) {
                continue;
            }
            result.add(new RagModels.RetrievedChunk(
                    id, c.docId(), c.kbId(), c.chunkIndex(),
                    docNames.getOrDefault(c.docId(), "未知文档"),
                    c.pageNo(), c.contentType(), c.content(),
                    score, v, k, vectorIds.get(id)));
        }
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<RagModels.RetrievedChunk> top = result.size() <= topK ? result : new ArrayList<>(result.subList(0, topK));
        metrics.recordRetrieval(top.size(), System.currentTimeMillis() - start);
        return top;
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}
