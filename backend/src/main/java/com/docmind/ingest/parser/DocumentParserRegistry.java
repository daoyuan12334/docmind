package com.docmind.ingest.parser;

import com.docmind.ingest.DocumentParser;
import com.docmind.kb.KbModels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** 解析器注册表：按模态/扩展名路由到具体实现。 */
@Component
@RequiredArgsConstructor
public class DocumentParserRegistry {

    private final List<DocumentParser> parsers;

    /**
     * 选择解析器。
     *
     * <p>先把模态归一化：调用方没给（或是兜底的 TEXT）时，按扩展名推断。
     * 这样每个解析器拿到的都是「模态 + 扩展名」两个信号，
     * 谁同时认可才归谁——而不是只看其中一个，让 bean 顺序决定命运。
     *
     * <p>关键点：扩展名判断必须是精确集合匹配。早先音频解析器用
     * {@code "…,mp4a".contains("mp4")} 命中了视频，把 .mp4 送去 ASR。
     */
    public DocumentParser select(KbModels.Modality modality, String ext) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        KbModels.Modality m = (modality == null || modality == KbModels.Modality.TEXT)
                ? detectModality(e)
                : modality;
        for (DocumentParser parser : parsers) {
            if (parser.supports(m, e)) {
                return parser;
            }
        }
        throw new IllegalArgumentException("不支持的文档类型: " + e);
    }

    /** 按扩展名推断模态，作为解析器选择的兜底依据。 */
    public static KbModels.Modality detectModality(String ext) {
        if (ext == null) {
            return KbModels.Modality.TEXT;
        }
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "pdf" -> KbModels.Modality.PDF;
            case "png", "jpg", "jpeg", "gif", "bmp", "webp", "tiff", "tif" -> KbModels.Modality.IMAGE;
            case "xlsx", "xls", "csv" -> KbModels.Modality.SHEET;
            case "pptx" -> KbModels.Modality.SLIDE;
            case "mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "amr" -> KbModels.Modality.AUDIO;
            case "mp4", "mov", "mkv", "avi", "flv", "wmv", "webm", "m4v" -> KbModels.Modality.VIDEO;
            default -> KbModels.Modality.TEXT;
        };
    }
}
