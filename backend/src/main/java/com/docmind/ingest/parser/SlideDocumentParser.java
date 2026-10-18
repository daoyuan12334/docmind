package com.docmind.ingest.parser;

import com.docmind.config.AppProperties;
import com.docmind.ingest.DocumentParser;
import com.docmind.ingest.ParsedDocument;
import com.docmind.ingest.multimodal.MultimodalService;
import com.docmind.kb.KbModels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/** PPT 解析：抽取每页文本，并对内嵌图片/图表调用视觉模型生成说明。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlideDocumentParser implements DocumentParser {

    private final MultimodalService multimodal;
    private final AppProperties props;

    @Override
    public boolean supports(KbModels.Modality modality, String ext) {
        // 只支持 OOXML 的 pptx。旧版二进制 .ppt（HSLF）是另一套模型，
        // 这里不谎称支持——交由 DocumentParserRegistry 给出明确的「格式不支持」提示。
        if ("ppt".equalsIgnoreCase(ext)) {
            return false;
        }
        return modality == KbModels.Modality.SLIDE || "pptx".equalsIgnoreCase(ext);
    }

    @Override
    public ParsedDocument parse(KbModels.Document doc, byte[] bytes) throws Exception {
        ParsedDocument parsed = new ParsedDocument().modality(KbModels.Modality.SLIDE);
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int index = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                index++;
                StringBuilder text = new StringBuilder();
                // 单页解析失败不应该拖垮整份文档：降级到原始 XML 抽文本，保证还能被检索到
                try {
                    collectSlideText(slide, text, parsed, doc.id(), index);
                } catch (Exception e) {
                    log.warn("幻灯片解析异常，改用 XML 兜底 docId={} page={}", doc.id(), index, e);
                    text.append(extractTextFromXml(slide));
                }
                if (!text.isEmpty()) {
                    parsed.add(text.toString(), index, ParsedDocument.BlockType.TEXT);
                }
            }
            parsed.pageCount(index);
        }
        return parsed;
    }

    /** 抽取一页的文本与图片说明；形状级别再做一次容错。 */
    private void collectSlideText(XSLFSlide slide, StringBuilder text, ParsedDocument parsed,
                                  long docId, int pageNo) {
        for (XSLFShape shape : slide.getShapes()) {
            try {
                if (shape instanceof XSLFTextShape ts) {
                    String t = ts.getText();
                    if (t != null && !t.isBlank()) {
                        text.append(t).append('\n');
                    }
                } else if (shape instanceof XSLFPictureShape picShape) {
                    XSLFPictureData pic = picShape.getPictureData();
                    if (pic == null) {
                        continue;
                    }
                    byte[] data = pic.getData();
                    if (data.length > props.getMultimodal().getMaxImageBytes()) {
                        continue;
                    }
                    String mime = "image/" + (pic.getContentType() == null ? "png" : subtype(pic.getContentType()));
                    try {
                        String desc = multimodal.describeImage(data, mime,
                                "这是幻灯片中的一张图，请说明它表达的信息，并输出图中可见文字。");
                        parsed.add(desc, pageNo, ParsedDocument.BlockType.IMAGE_CAPTION);
                    } catch (Exception e) {
                        log.warn("幻灯片图片理解失败 docId={} page={}", docId, pageNo, e);
                    }
                }
            } catch (Exception e) {
                // 单个形状坏了（缺少 spPr、异常几何定义等）只跳过它，不影响同页其它内容
                log.warn("幻灯片形状解析失败，已跳过 docId={} page={} shape={}",
                        docId, pageNo, shape.getClass().getSimpleName(), e);
            }
        }
    }

    /** XML 兜底：直接从 slide XML 里抠 <a:t> 文本，不依赖 POI 的对象模型。 */
    private String extractTextFromXml(XSLFSlide slide) {
        try {
            String xml = slide.getXmlObject().xmlText();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<a:t>(.*?)</a:t>", java.util.regex.Pattern.DOTALL).matcher(xml);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String t = m.group(1).replaceAll("<[^>]+>", "").trim();
                if (!t.isEmpty()) {
                    sb.append(t).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("幻灯片 XML 兜底也失败", e);
            return "";
        }
    }

    private String subtype(String contentType) {
        if (contentType.contains("/")) {
            return contentType.substring(contentType.indexOf('/') + 1);
        }
        return "png";
    }
}
