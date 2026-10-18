package com.docmind.rag;

import com.docmind.kb.ChunkRepository;
import com.docmind.kb.DocumentRepository;
import com.docmind.kb.KbModels;
import com.docmind.kb.KnowledgeBaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混合检索回归。
 *
 * <p>核心目标：确认「关键词通道」真的能召回，混合检索没有退化成纯向量检索。
 *
 * <p>背景：ChunkRepository.listByKb 曾把 limit / offset 传反，
 * SQL 变成 "LIMIT 0 OFFSET 5000"，永远返回空集，
 * 于是每个结果的 keywordScore 恒为 0，RRF 融合名存实亡。
 * 症状很隐蔽 —— 接口照样返回 200 和看似合理的分数，只是关键词那一路一直是空的。
 */
@SpringBootTest
@ActiveProfiles("test")
class HybridRetrievalTest {

    @Autowired
    private KnowledgeBaseRepository knowledgeBases;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private ChunkRepository chunks;
    @Autowired
    private KeywordSearchPort keywordSearch;

    @Test
    @DisplayName("分页参数未写反：limit=5000/offset=0 应取回全部，offset=1 应跳过首条")
    void listByKbHonoursLimitAndOffset() {
        long kbId = knowledgeBases.create("分页回归-" + System.nanoTime(), "回归",
                1L, "PRIVATE", "text-embedding-v4", 800, 120, 6);
        long docId = documents.create(kbId, "分页回归.md", "md", "text/markdown",
                100L, "ck-" + System.nanoTime(), "sk-" + System.nanoTime(),
                KbModels.Modality.TEXT, 1L);
        chunks.insertBatch(List.of(
                chunk(docId, kbId, 0, "年假天数按司龄计算：满 1 年 5 天。"),
                chunk(docId, kbId, 1, "差旅报销须在 15 个工作日内提交。"),
                chunk(docId, kbId, 2, "市内交通费每人每天不超过 200 元。")));

        // 关键回归点：若参数顺序写成 (kbId, offset, limit)，这里会拿到空列表
        assertEquals(3, chunks.listByKb(kbId, 5000, 0).size(),
                "listByKb(kbId, 5000, 0) 应返回全部切片");
        assertEquals(2, chunks.listByKb(kbId, 2, 1).size(),
                "limit=2 / offset=1 应返回两条");
    }

    @Test
    @DisplayName("关键词通道对中文查询必须召回，且命中的是含该词的那条切片")
    void keywordChannelRecallsChineseQuery() {
        long kbId = knowledgeBases.create("关键词通道-" + System.nanoTime(), "回归",
                1L, "PRIVATE", "text-embedding-v4", 800, 120, 6);
        long docId = documents.create(kbId, "关键词.md", "md", "text/markdown",
                100L, "ck-" + System.nanoTime(), "sk-" + System.nanoTime(),
                KbModels.Modality.TEXT, 1L);
        var ids = chunks.insertBatch(List.of(
                chunk(docId, kbId, 0, "差旅报销须在出差结束后 15 个工作日内提交。"),
                chunk(docId, kbId, 1, "年假天数按司龄计算：满 1 年 5 天，满 3 年 10 天。")));

        List<KeywordSearchPort.Hit> hits = keywordSearch.search(kbId, "年假", 10);

        assertFalse(hits.isEmpty(),
                "关键词通道一条都没召回 —— 先检查 ChunkRepository.listByKb 的 limit/offset 参数顺序");
        assertEquals(ids.get(1), hits.get(0).chunkId(),
                "含「年假」的切片应排第一");
        assertTrue(hits.get(0).score() > 0, "命中分数应大于 0");
    }

    @Test
    @DisplayName("分词：CJK 走 bigram 滑动，英文按单词且转小写")
    void tokenizerHandlesMixedScripts() {
        Set<String> zh = InMemoryKeywordSearch.tokenize("年假天数");
        assertTrue(zh.contains("年假"), "中文应产生 bigram，实际: " + zh);
        assertTrue(zh.contains("假天"), "bigram 应滑动覆盖，实际: " + zh);

        Set<String> en = InMemoryKeywordSearch.tokenize("Annual Leave Policy");
        assertTrue(en.contains("annual"), "英文应转小写并整词切分，实际: " + en);
        assertTrue(en.contains("leave"), "英文应转小写并整词切分，实际: " + en);
    }

    @Test
    @DisplayName("分词：空串 / null / 纯空白均返回空集，不抛异常")
    void tokenizerIsNullSafe() {
        assertTrue(InMemoryKeywordSearch.tokenize("").isEmpty(), "空串应返回空集");
        assertTrue(InMemoryKeywordSearch.tokenize(null).isEmpty(), "null 应返回空集");
        assertTrue(InMemoryKeywordSearch.tokenize("   ").isEmpty(), "纯空白应返回空集");
    }

    private static ChunkRepository.ChunkDraft chunk(long docId, long kbId, int idx, String content) {
        return new ChunkRepository.ChunkDraft(docId, kbId, idx, content,
                "ck-" + idx, 12, 1, "TEXT", null);
    }
}
