package com.docmind.ingest.parser;

import com.docmind.config.AppProperties;
import com.docmind.ingest.DocumentParser;
import com.docmind.ingest.ParsedDocument;
import com.docmind.ingest.multimodal.MultimodalService;
import com.docmind.kb.KbModels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * PDF 解析：文本层可用时直接抽取；判定为扫描页（文本过少）时渲染成图片交给视觉模型 OCR。
 * 这是企业文档场景最常见的情况：导出 PDF、扫描件、影印合同。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfDocumentParser implements DocumentParser {

    private static final int SCAN_PAGE_TEXT_THRESHOLD = 30;

    private final MultimodalService multimodal;
    private final AppProperties props;

    @Override
    public boolean supports(KbModels.Modality modality, String ext) {
        return modality == KbModels.Modality.PDF || "pdf".equalsIgnoreCase(ext);
    }

    @Override
    public ParsedDocument parse(KbModels.Document doc, byte[] bytes) throws Exception {
        ParsedDocument parsed = new ParsedDocument().modality(KbModels.Modality.PDF);
        try (PDDocument pd = Loader.loadPDF(bytes)) {
            int pages = pd.getNumberOfPages();
            parsed.pageCount(pages);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            PDFRenderer renderer = new PDFRenderer(pd);
            int visionBudget = props.getMultimodal().getVisionMaxPages();
            int visionUsed = 0;

            for (int i = 1; i <= pages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(pd);
                if (text != null && text.trim().length() >= SCAN_PAGE_TEXT_THRESHOLD) {
                    parsed.add(text, i, ParsedDocument.BlockType.TEXT);
                    continue;
                }
                if (!props.getMultimodal().isOcrEnabled() || visionUsed >= visionBudget) {
                    if (text != null && !text.isBlank()) {
                        parsed.add(text, i, ParsedDocument.BlockType.TEXT);
                    }
                    continue;
                }
                byte[] png = render(renderer, i - 1);
                if (png.length > props.getMultimodal().getMaxImageBytes()) {
                    log.warn("扫描页渲染过大跳过 OCR docId={} page={}", doc.id(), i);
                    continue;
                }
                // 提示语同时覆盖两种情况：有文字就转文字，纯图形页就描述画面。
                // 只问「识别文字」时，纯图页会得到「无文本内容」这种毫无检索价值的空切片。
                String ocr = multimodal.describeImage(png, "image/png",
                        "请处理这一页扫描件：先逐行输出识别到的印刷体文字（保留原文，不要改写）；"
                                + "如果是表格请转为 Markdown 表格；"
                                + "若本页确实没有文字，则用 1-2 句话描述页面上的图形/图示内容。"
                                + "不要添加任何解释性前缀。");
                visionUsed++;
                if (isBlankScanResult(ocr)) {
                    log.warn("扫描页 OCR 返回空 docId={} page={}", doc.id(), i);
                    continue;
                }
                parsed.add(ocr, i, ParsedDocument.BlockType.OCR, "{\"source\":\"vision-ocr\"}");
            }
        }
        return parsed;
    }

    /**
     * 视觉模型常把「没内容」表达成各种说法（"无文本内容。"、"本页为空白页"…），
     * 统一当成空处理，别让这种空切片写进向量索引污染检索。
     * 公开是为了能直接单测——这个判断错了就会静默丢页或塞垃圾进索引。
     */
    public static boolean isBlankScanResult(String text) {
        if (text == null) {
            return true;
        }
        String t = text.replaceAll("[\\s。．.、,，:：;；\"'“”‘’（）()\\[\\]【】-]", "");
        return t.isEmpty() || t.contains("无文本") || t.contains("没有文字") || t.contains("空白页");
    }

    private byte[] render(PDFRenderer renderer, int pageIndex) throws Exception {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, 150f, ImageType.RGB);
        // 单页像素过大时降采样，控制 token 成本
        int maxWidth = 1600;
        if (image.getWidth() > maxWidth) {
            int height = (int) (image.getHeight() * (maxWidth / (double) image.getWidth()));
            java.awt.Image scaled = image.getScaledInstance(maxWidth, height, java.awt.Image.SCALE_SMOOTH);
            BufferedImage target = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = target.createGraphics();
            g.drawImage(scaled, 0, 0, null);
            g.dispose();
            image = target;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
