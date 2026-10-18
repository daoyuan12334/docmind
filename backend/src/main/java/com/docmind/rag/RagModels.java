package com.docmind.rag;

import java.util.List;

/** RAG 检索结果模型。 */
public final class RagModels {

    private RagModels() {
    }

    /** 召回候选。score 为融合后的综合分，vectorScore / keywordScore 保留原始分便于调优。 */
    public record RetrievedChunk(long chunkId, long docId, long kbId, int chunkIndex, String docName,
                                 Integer pageNo, String contentType, String content,
                                 double score, double vectorScore, double keywordScore, String vectorId) {
    }

    /** 答案引用：前端据此渲染「来源：文档名 · 第 N 页」。 */
    public record Citation(int index, long docId, String docName, Integer pageNo, String snippet, double score) {
    }

    public record RagAnswer(String answer, List<Citation> citations, int promptTokens, int completionTokens,
                            int latencyMs, String model, String traceId) {
    }

    public record RetrieveResult(List<RetrievedChunk> chunks, long costMs) {
    }
}
