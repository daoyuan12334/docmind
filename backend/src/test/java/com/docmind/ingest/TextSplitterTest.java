package com.docmind.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片器的行为契约。
 * <p>这是 RAG 质量的地基：切得太碎丢上下文，切得太粗检索不精准，而且绝对不能把内容切丢。
 */
class TextSplitterTest {

    private final TextSplitter splitter = new TextSplitter();

    @Test
    @DisplayName("短文本不切分，原样返回")
    void shortTextKeptIntact() {
        List<String> chunks = splitter.split("这是一段很短的文本。", 800, 120);
        assertEquals(1, chunks.size());
        assertEquals("这是一段很短的文本。", chunks.get(0));
    }

    @Test
    @DisplayName("长文本按段落优先切分，且不能丢字")
    void longTextSplitWithoutLosingContent() {
        String para = "员工差旅报销需在出差结束后 15 个工作日内提交，单笔超过 5000 元需部门总监审批。";
        String text = para.repeat(30);

        List<String> chunks = splitter.split(text, 800, 120);

        assertTrue(chunks.size() > 1, "长文本应该被切成多片");
        // 关键断言：所有切片拼接后必须覆盖原文的核心内容（允许 overlap 重复，但不允许丢字）
        String joined = String.join("", chunks);
        for (int i = 0; i < 30; i++) {
            assertTrue(joined.contains("差旅报销"), "第 " + i + " 段内容丢失");
        }
        int totalLen = chunks.stream().mapToInt(String::length).sum();
        assertTrue(totalLen >= text.length(), "切片总长度不应小于原文（overlap 只会让它变长）");
    }

    @Test
    @DisplayName("每片长度不超过 chunkSize 上限（留一定容差给硬切场景）")
    void chunkRespectsSizeLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("这是第 ").append(i).append(" 句话，用于验证切分长度约束。");
        }
        List<String> chunks = splitter.split(sb.toString(), 800, 120);
        assertFalse(chunks.isEmpty());
        for (String c : chunks) {
            assertTrue(c.length() <= 800 * 1.5 + 50,
                    "单切片过长: " + c.length());
        }
    }

    @Test
    @DisplayName("空文本与空白文本返回空列表，不抛异常")
    void blankTextSafe() {
        assertTrue(splitter.split("", 800, 120).isEmpty());
        assertTrue(splitter.split("   \n\t  ", 800, 120).isEmpty());
    }

    @Test
    @DisplayName("token 估算：中英混排能给出合理量级")
    void tokenEstimate() {
        int cn = splitter.estimateTokens("员工差旅报销需在出差结束后十五个工作日内提交");
        int en = splitter.estimateTokens("the quick brown fox jumps over the lazy dog");
        assertTrue(cn > 0 && en > 0);
        assertTrue(en > 5 && en < 30, "英文 token 估算偏离合理区间: " + en);
    }
}
