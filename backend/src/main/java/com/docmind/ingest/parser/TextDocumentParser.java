package com.docmind.ingest.parser;

import com.docmind.ingest.DocumentParser;
import com.docmind.ingest.ParsedDocument;
import com.docmind.kb.KbModels;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** 文本文档解析：Markdown 走结构化读取器，其余（docx/txt/html/rtf/odt）走 Tika。 */
@Component
@RequiredArgsConstructor
public class TextDocumentParser implements DocumentParser {

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
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource,
                        MarkdownDocumentReaderConfig.defaultConfig());
                for (org.springframework.ai.document.Document d : reader.get()) {
                    parsed.add(d.getText(), null, ParsedDocument.BlockType.TEXT);
                }
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
}
