package com.docmind.ingest;

import com.docmind.ingest.parser.TextDocumentParser;
import com.docmind.kb.KbModels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markdown 标题上下文回归。
 *
 * <p>背景：Spring AI 的 MarkdownDocumentReader 把标题只放进 metadata 的 title，
 * getText() 里没有标题。早期实现直接取 getText()，导致「接口路径」「章节名」
 * 这类最该被检索到的内容根本没进索引；同时代码块、单行表格会变成几个字的碎片切片。
 */
class MarkdownHeadingContextTest {

    private final TextDocumentParser parser = new TextDocumentParser();

    /** 贴近真实 API 文档的结构：标题 → 请求：→ 代码块 → 响应说明。 */
    private static final String MD = """
            # API 接口规范

            ## 问答接口

            ### POST /api/chat/{kbId}/ask
            请求：
            ```json
            { "question": "差旅住宿标准是多少" }
            ```
            响应 data 含 answer、sources 与 conversationId。
            参数 topK 省略时取知识库配置值。

            ### POST /api/chat/{kbId}/stream
            请求：
            ```json
            { "question": "差旅住宿标准是多少" }
            ```
            响应为 SSE 事件流，事件依次为 meta、delta、done，出错时推 error。

            ## 检索接口

            ### GET /api/chat/{kbId}/retrieve
            只召回不生成，参数是 q 与 topK。
            响应为切片数组，含 documentId、fileName、content、score。
            """;

    private ParsedDocument parse(String md) {
        return parser.parse(doc("x.md"), md.getBytes(StandardCharsets.UTF_8));
    }

    private KbModels.Document doc(String name) {
        // 第 4 个参数是 ext，传空会走 Tika 兜底分支，测不到 Markdown 读取器
        return new KbModels.Document(99L, 1L, name, "md", "text/markdown", 0L, "", "",
                KbModels.DocStatus.PENDING, "PENDING", 0, null, 0, 0, 0,
                KbModels.Modality.TEXT, null, null, 1L, null, null, false);
    }

    @Test
    @DisplayName("标题（含接口路径）必须回到正文中，否则检索永远命中不了")
    void headingTextIsKept() {
        String all = parse(MD).allText();
        assertTrue(all.contains("POST /api/chat/{kbId}/ask"), "接口路径丢失，全文=\n" + all);
        assertTrue(all.contains("POST /api/chat/{kbId}/stream"), "流式接口路径丢失");
        assertTrue(all.contains("GET /api/chat/{kbId}/retrieve"), "检索接口路径丢失");
    }

    @Test
    @DisplayName("标题要作为前缀挂在正文前面，读者才知道这段出自哪一节")
    void headingIsPrefixedToBody() {
        String all = parse(MD).allText();
        assertTrue(all.contains("POST /api/chat/{kbId}/ask\n请求"),
                "标题没有作为前缀挂在正文前，全文=\n" + all);
        assertTrue(all.contains("GET /api/chat/{kbId}/retrieve\n只召回不生成"),
                "检索接口正文前缺少标题前缀，全文=\n" + all);
    }

    @Test
    @DisplayName("换标题要开新块，不能把两节内容揉进同一片")
    void newHeadingStartsNewBlock() {
        String all = parse(MD).allText();
        assertFalse(all.contains("只召回不生成")
                        && all.indexOf("GET /api/chat/{kbId}/retrieve") < all.indexOf("只召回不生成") - 400,
                "检索接口与上一节粘在了同一片里");
        for (ParsedDocument.Block b : parse(MD).blocks()) {
            assertFalse(b.text().contains("POST /api/chat/{kbId}/ask")
                            && b.text().contains("GET /api/chat/{kbId}/retrieve"),
                    "两个接口被塞进同一个切片: " + b.text());
        }
    }

    @Test
    @DisplayName("代码块与单行段落不能再变成几个字的碎片")
    void noTinyFragments() {
        var blocks = parse(MD).blocks();
        assertFalse(blocks.isEmpty(), "没解析出任何块");
        for (ParsedDocument.Block b : blocks) {
            assertTrue(b.text().length() >= 40,
                    "出现碎片切片（" + b.text().length() + " 字）: " + b.text());
        }
    }

    @Test
    @DisplayName("真实 API 文档：接口路径要能被检索到")
    void realApiDocKeepsPaths() throws Exception {
        Path p = Path.of("../samples/demo/API接口规范.md");
        if (!Files.exists(p)) {
            return; // 演示文档未生成时跳过，不算失败
        }
        ParsedDocument parsed = parser.parse(doc("API接口规范.md"), Files.readAllBytes(p));
        String all = parsed.allText();
        assertTrue(all.contains("/api/chat/{kbId}/ask"),
                "问答接口路径未进入正文，块数=" + parsed.blocks().size() + " 全文后 2500 字=\n"
                        + all.substring(Math.max(0, all.length() - 2500)));
        assertTrue(all.contains("/api/chat/{kbId}/stream"), "流式接口路径未进入正文");
        assertTrue(all.contains("/api/kb/documents/{docId}/retry"), "重试接口路径未进入正文");
    }
}
