package com.docmind.ingest;

import com.docmind.kb.KbModels;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析结果。一个文档解析为若干 Block，每个 Block 带页码与内容类型，
 * 供后续按类型区别切分（表格整块保留、图片用描述文本、音频用转写稿）。
 */
public final class ParsedDocument {

    public enum BlockType {
        TEXT, TABLE, IMAGE_CAPTION, OCR, AUDIO_TRANSCRIPT, VIDEO_FRAME
    }

    public record Block(String text, Integer pageNo, BlockType type, String meta) {
    }

    private final List<Block> blocks = new ArrayList<>();
    private int pageCount;
    private String summary;
    private KbModels.Modality modality;

    public ParsedDocument add(String text, Integer pageNo, BlockType type, String meta) {
        if (text != null && !text.isBlank()) {
            blocks.add(new Block(text.trim(), pageNo, type, meta));
        }
        return this;
    }

    public ParsedDocument add(String text, Integer pageNo, BlockType type) {
        return add(text, pageNo, type, null);
    }

    public List<Block> blocks() {
        return blocks;
    }

    public int pageCount() {
        return pageCount;
    }

    public ParsedDocument pageCount(int pageCount) {
        this.pageCount = pageCount;
        return this;
    }

    public String summary() {
        return summary;
    }

    public ParsedDocument summary(String summary) {
        this.summary = summary;
        return this;
    }

    public KbModels.Modality modality() {
        return modality;
    }

    public ParsedDocument modality(KbModels.Modality modality) {
        this.modality = modality;
        return this;
    }

    public String allText() {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            sb.append(b.text()).append('\n');
        }
        return sb.toString();
    }
}
