package com.docmind.ingest.parser;

import com.docmind.ingest.DocumentParser;
import com.docmind.ingest.ParsedDocument;
import com.docmind.kb.KbModels;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** 文本文档解析：Markdown 按标题自行分块，其余（docx/txt/html/rtf/odt）走 Tika。 */
@Component
@RequiredArgsConstructor
public class TextDocumentParser implements DocumentParser {

    /** 单个文本块攒到这个长度就吐出去，避免长章节被切成一堆小片。 */
    private static final int TARGET_BLOCK_CHARS = 600;

    /** 攒不够这个长度的章节不单独成片，向前合并，避免出现几十字的碎片。 */
    private static final int MIN_BLOCK_CHARS = 150;

    private static final Set<String> EXTS = Set.of(
            "md", "markdown", "txt", "text", "html", "htm", "rtf", "odt",
            "doc", "docx", "json", "xml", "csv", "log");

    @Override
    public boolean supports(KbModels.Modality modality, String ext) {
        return modality == KbModels.Modality.TEXT
                && ext != null && EXTS.contains(ext.toLowerCase(Locale.ROOT));
    }

    @Override
    public ParsedDocument parse(KbModels.Document doc, byte[] bytes) {
        ParsedDocument parsed = new ParsedDocument().modality(KbModels.Modality.TEXT);
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return doc.name();
            }
        };
        try {
            if ("md".equalsIgnoreCase(doc.ext()) || "markdown".equalsIgnoreCase(doc.ext())) {
                readMarkdown(bytes, parsed);
            } else {
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                for (org.springframework.ai.document.Document d : reader.get()) {
                    parsed.add(d.getText(), null, ParsedDocument.BlockType.TEXT);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("文本文档解析失败: " + doc.name() + " -> " + e.getMessage(), e);
        }
        return parsed.pageCount(Math.max(1, parsed.blocks().size()));
    }

    /**
     * Markdown 按标题分块。
     *
     * <p>这里没有用 Spring AI 的 MarkdownDocumentReader，实测它有两个致命问题：
     * <ol>
     *   <li>标题只进 metadata 的 {@code title}，{@code getText()} 里没有；
     *       标题后紧跟代码块时连 title 都拿不到。直接入库的话，「接口路径」
     *       「章节名」这类最该被搜到的信息全丢了 —— 问「非流式问答接口是哪个路径」，
     *       答案就藏在 {@code ### POST /api/chat/{kbId}/ask} 这个标题里。</li>
     *   <li>代码块、单行表格、只有一句的段落各自成块，产生大量几个字的碎片切片。</li>
     * </ol>
     *
     * <p>因此改为直接按行扫原始文本：遇到 {@code #} 标题就切一块并把标题作为前缀拼回正文，
     * 同一标题下的内容攒够长度再输出（末尾太短的块向前合并，避免碎片）。
     */
    private void readMarkdown(byte[] bytes, ParsedDocument parsed) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder buffer = new StringBuilder();
        String heading = "";
        boolean inFence = false;
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.replace("\r", "").strip();
            if (line.startsWith("```")) {
                inFence = !inFence;
            }
            String title = inFence ? null : headingOf(line);
            if (title != null) {
                if (buffer.length() >= MIN_BLOCK_CHARS) {
                    flush(buffer, heading, parsed);
                } else if (buffer.length() > 0) {
                    // 上一节太短：不单独成片，把它的标题固化进正文后与下一节合并
                    buffer.insert(0, heading.isEmpty() ? "" : heading + "\n");
                }
                heading = title;
                continue;
            }
            if (line.isEmpty()) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(line);
            if (buffer.length() >= TARGET_BLOCK_CHARS) {
                flush(buffer, heading, parsed);
            }
        }
        flush(buffer, heading, parsed);
    }

    /** 形如 {@code ## 标题} 的行返回标题文本，否则返回 null。 */
    private static String headingOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') {
            i++;
        }
        if (i == 0 || i > 6 || i >= line.length()) {
            return null;
        }
        char next = line.charAt(i);
        if (next != ' ' && next != '\t') {
            return null;
        }
        String title = line.substring(i).strip();
        return title.isEmpty() ? null : title;
    }

    private void flush(StringBuilder buffer, String heading, ParsedDocument parsed) {
        if (buffer.length() == 0) {
            return;
        }
        String body = buffer.toString().trim();
        buffer.setLength(0);
        parsed.add(heading.isEmpty() ? body : heading + "\n" + body, null, ParsedDocument.BlockType.TEXT);
    }
}
