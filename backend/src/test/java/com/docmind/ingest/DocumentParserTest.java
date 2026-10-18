package com.docmind.ingest;

import com.docmind.ingest.parser.DocumentParserRegistry;
import com.docmind.ingest.parser.PdfDocumentParser;
import com.docmind.kb.KbModels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析器的端到端契约：给定真实文件字节，必须产出可检索的文本块。
 *
 * <p>图片/音频/视频解析器会调模型，这里不覆盖（由 IT 手动验证）；
 * 本组用例只覆盖纯本地可完成的三类：Markdown/文本、表格 xlsx、幻灯片 pptx。
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentParserTest {

    @Autowired
    private DocumentParserRegistry registry;

    private static final Path SAMPLES = Path.of("../samples");

    private KbModels.Document doc(String name) {
        return new KbModels.Document(99L, 1L, name, "", "", 0L, "", "",
                KbModels.DocStatus.PENDING, "PENDING", 0, null, 0, 0, 0,
                KbModels.Modality.TEXT, null, null, 1L, null, null, false);
    }

    private byte[] sample(String name) throws Exception {
        Path p = SAMPLES.resolve(name);
        assertTrue(Files.exists(p), "缺少样本文件: " + p.toAbsolutePath() + "（请先生成 samples）");
        return Files.readAllBytes(p);
    }

    @Test
    @DisplayName("Markdown：解析出正文并包含关键段落")
    void parseMarkdown() throws Exception {
        byte[] bytes = "docmind-test.md".getBytes(StandardCharsets.UTF_8);
        ParsedDocument parsed = registry
                .select(KbModels.Modality.TEXT, "md")
                .parse(doc("docmind-test.md"),
                        Files.readAllBytes(SAMPLES.resolve("docmind-test.md")));

        assertFalse(parsed.blocks().isEmpty(), "应产出至少一个文本块");
        String all = parsed.allText();
        assertTrue(all.contains("差旅报销"), "正文丢失：未找到『差旅报销』");
        assertTrue(all.contains("年假"), "正文丢失：未找到『年假』");
        assertDoesNotThrow(parsed::allText);
        assertTrue(bytes.length > 0);
    }

    @Test
    @DisplayName("Excel：多个 sheet 都要被抽出来，且表头与数值都在")
    void parseExcel() throws Exception {
        ParsedDocument parsed = registry
                .select(KbModels.Modality.SHEET, "xlsx")
                .parse(doc("fixture.xlsx"), sample("fixture.xlsx"));

        assertFalse(parsed.blocks().isEmpty());
        String all = parsed.allText();
        assertTrue(all.contains("报销标准") || all.contains("一线城市"), "sheet1 内容缺失: " + all);
        assertTrue(all.contains("研发中心") || all.contains("市场部"), "sheet2 内容缺失: " + all);
        assertTrue(parsed.pageCount() >= 2, "两个 sheet 应至少计 2 页，实际 " + parsed.pageCount());
    }

    @Test
    @DisplayName("PPTX：正常结构能抽到每页文字")
    void parseSlide() throws Exception {
        ParsedDocument parsed = registry
                .select(KbModels.Modality.SLIDE, "pptx")
                .parse(doc("fixture.pptx"), sample("fixture.pptx"));

        assertFalse(parsed.blocks().isEmpty(), "幻灯片未抽出任何文本");
        String all = parsed.allText();
        assertTrue(all.contains("差旅报销制度"), "第一页标题缺失: " + all);
        assertTrue(all.contains("年假规则"), "第二页标题缺失: " + all);
        assertTrue(parsed.pageCount() >= 2, "应解析出 2 页，实际 " + parsed.pageCount());
    }

    @Test
    @DisplayName("旧版 .ppt（二进制）不再被谎称支持，必须明确报错")
    void legacyPptRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.select(KbModels.Modality.SLIDE, "ppt"));
    }

    @Test
    @DisplayName("未知扩展名走文本兜底，但注册表仍要能路由")
    void unknownExtensionFallsBackToText() {
        assertDoesNotThrow(() -> registry.select(KbModels.Modality.TEXT, "txt"));
    }

    @Test
    @DisplayName("扫描页 OCR 空结果要被识别出来，不能当成有效切片写进索引")
    void blankScanResultDetected() {
        // 视觉模型表达「没内容」的方式有很多种，都得判成空
        assertTrue(PdfDocumentParser.isBlankScanResult(null));
        assertTrue(PdfDocumentParser.isBlankScanResult("   \n\t "));
        assertTrue(PdfDocumentParser.isBlankScanResult("无文本内容。"));
        assertTrue(PdfDocumentParser.isBlankScanResult("本页为空白页"));
        assertTrue(PdfDocumentParser.isBlankScanResult("。"));
        // 有实质内容时必须放行
        assertFalse(PdfDocumentParser.isBlankScanResult("第一条 报销时限：15 个工作日内提交"));
        assertFalse(PdfDocumentParser.isBlankScanResult("该页包含一个红色矩形与蓝色圆形"));
    }

    @Test
    @DisplayName("文本版 PDF 能抽出正文，不触发 OCR")
    void textPdfExtractedByTextLayer() throws Exception {
        Path pdf = Path.of("../samples/fixture.pdf");
        assertTrue(Files.exists(pdf), "缺少样本 ../samples/fixture.pdf");

        KbModels.Document doc = new KbModels.Document(
                0L, 0L, "fixture.pdf", "pdf", "application/pdf", Files.size(pdf),
                "t", "t", KbModels.DocStatus.PENDING, "PENDING", 0, null,
                0, 0, 0, KbModels.Modality.PDF, null, null, 0L,
                null, null, false);
        var parsed = registry.select(KbModels.Modality.PDF, "pdf")
                .parse(doc, Files.readAllBytes(pdf));

        String all = parsed.allText();
        assertTrue(all.contains("报销时限"), "PDF 正文未抽出: " + all);
        assertTrue(all.contains("住宿标准"), "PDF 正文缺失第二条: " + all);
        assertTrue(parsed.blocks().stream().noneMatch(b -> b.type() == ParsedDocument.BlockType.OCR),
                "有文本层就不该走 OCR（会白白烧 token）");
    }
}
