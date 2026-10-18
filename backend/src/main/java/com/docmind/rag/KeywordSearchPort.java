package com.docmind.rag;

import java.util.List;

/** 关键词召回通道。生产走 PostgreSQL 全文检索，开发走内存词频/BM25。 */
public interface KeywordSearchPort {

    List<Hit> search(long kbId, String query, int topK);

    /** 关键词命中的切片。 */
    record Hit(long chunkId, double score) {
    }
}
