package com.docmind.ingest;

import com.docmind.ingest.parser.AudioDocumentParser;
import com.docmind.ingest.parser.DocumentParserRegistry;
import com.docmind.ingest.parser.ImageDocumentParser;
import com.docmind.ingest.parser.PdfDocumentParser;
import com.docmind.ingest.parser.SheetDocumentParser;
import com.docmind.ingest.parser.SlideDocumentParser;
import com.docmind.ingest.parser.TextDocumentParser;
import com.docmind.ingest.parser.VideoDocumentParser;
import com.docmind.kb.KbModels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 解析路由回归测试。
 *
 * <p>这里的用例不是为了凑数，而是守着一个真实的线上事故：
 * 音频解析器早先把扩展名集合写成字符串 {@code "mp3,wav,...,mp4a"}，
 * 判断又用 {@code EXTS.contains(ext)} —— {@code "mp4a"} 里含 {@code "mp4"}，
 * 于是 .mp4 被当成音频送去 ASR，报出极具误导性的
 * “The provided URL does not appear to be valid”。
 * 与之配套的还有 {@code contains("")} 对空扩展名恒真的问题。
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentParserRoutingTest {

    @Autowired
    private DocumentParserRegistry registry;

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "md,       TextDocumentParser",
            "docx,     TextDocumentParser",
            "pdf,      PdfDocumentParser",
            "png,      ImageDocumentParser",
            "jpeg,     ImageDocumentParser",
            "xlsx,     SheetDocumentParser",
            "csv,      SheetDocumentParser",
            "pptx,     SlideDocumentParser",
            "mp3,      AudioDocumentParser",
            "wav,      AudioDocumentParser",
            "m4a,      AudioDocumentParser",
            "mp4,      VideoDocumentParser",
            "mov,      VideoDocumentParser",
            "m4v,      VideoDocumentParser"
    })
    @DisplayName("扩展名路由到正确的解析器（mp4 必须走视频，不能落进音频）")
    void routesByExtension(String ext, String expectedParser) {
        DocumentParser actual = registry.select(DocumentParserRegistry.detectModality(ext), ext);
        assertEquals(expectedParser, actual.getClass().getSimpleName(), ext + " 路由错误");
    }

    @Test
    @DisplayName("显式模态优先：即便扩展名缺失，VIDEO 也走视频解析器")
    void explicitModalityWins() {
        assertInstanceOf(VideoDocumentParser.class,
                registry.select(KbModels.Modality.VIDEO, null));
        assertInstanceOf(AudioDocumentParser.class,
                registry.select(KbModels.Modality.AUDIO, null));
        assertInstanceOf(ImageDocumentParser.class,
                registry.select(KbModels.Modality.IMAGE, null));
    }

    @Test
    @DisplayName("音频容器集合里不能有 mp4/m4v：容器型扩展名一律归视频")
    void audioParserMustNotClaimVideoContainers() {
        AudioDocumentParser audio = new AudioDocumentParser(null, null);
        assertEquals(false, audio.supports(null, "mp4"), "mp4 被音频解析器认领了");
        assertEquals(false, audio.supports(null, "m4v"), "m4v 被音频解析器认领了");
        assertEquals(false, audio.supports(null, "webm"), "webm 被音频解析器认领了");
        assertEquals(true, audio.supports(null, "mp3"));
        assertEquals(true, audio.supports(null, "m4a"));
    }

    @Test
    @DisplayName("空/未知扩展名不能靠 contains(\"\") 蒙混过关")
    void blankExtensionIsRejected() {
        // 空扩展名不能命中任何解析器
        assertThrows(IllegalArgumentException.class, () -> registry.select(null, ""));
        // 未知扩展名要有明确报错，而不是被某个解析器瞎接走
        assertThrows(IllegalArgumentException.class, () -> registry.select(null, "xyz"));
    }

    @Test
    @DisplayName("detectModality 与解析器路由保持一致")
    void detectModalityMatchesRouting() {
        assertEquals(KbModels.Modality.VIDEO, DocumentParserRegistry.detectModality("MP4"));
        assertEquals(KbModels.Modality.AUDIO, DocumentParserRegistry.detectModality("MP3"));
        assertEquals(KbModels.Modality.SHEET, DocumentParserRegistry.detectModality("XLSX"));
        assertEquals(KbModels.Modality.PDF, DocumentParserRegistry.detectModality("PDF"));
        assertEquals(KbModels.Modality.TEXT, DocumentParserRegistry.detectModality(null));
    }
}
