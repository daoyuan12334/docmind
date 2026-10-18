package com.docmind.ingest.parser;

import com.docmind.config.AppProperties;
import com.docmind.ingest.DocumentParser;
import com.docmind.ingest.ParsedDocument;
import com.docmind.ingest.multimodal.MultimodalService;
import com.docmind.kb.KbModels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** 图片解析：截图、照片、流程图、票据——统一交给视觉模型生成可检索的文字描述。 */
@Component
@RequiredArgsConstructor
public class ImageDocumentParser implements DocumentParser {

    private static final Set<String> EXTS =
            Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "tiff", "tif");

    private final MultimodalService multimodal;
    private final AppProperties props;

    @Override
    public boolean supports(KbModels.Modality modality, String ext) {
        return modality == KbModels.Modality.IMAGE
                || (ext != null && EXTS.contains(ext.toLowerCase(Locale.ROOT)));
    }

    @Override
    public ParsedDocument parse(KbModels.Document doc, byte[] bytes) {
        String mime = guessMime(doc.ext());
        if (bytes.length > props.getMultimodal().getMaxImageBytes()) {
            throw new IllegalStateException("图片超过 " + props.getMultimodal().getMaxImageBytes() / 1024 / 1024 + "MB 限制");
        }
        ParsedDocument parsed = new ParsedDocument().modality(KbModels.Modality.IMAGE).pageCount(1);
        String prompt = """
                请详细描述这张图片，并输出其中可见的全部文字（OCR）。
                如果是图表，请说明趋势、坐标轴和关键数值。
                输出简体中文，按「描述」和「文字内容」两部分组织。
                """;
        String text = multimodal.describeImage(bytes, mime, prompt);
        parsed.add(text, 1, ParsedDocument.BlockType.IMAGE_CAPTION);
        return parsed;
    }

    private String guessMime(String ext) {
        if (ext == null) {
            return "image/png";
        }
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "tiff", "tif" -> "image/tiff";
            default -> "image/png";
        };
    }
}
