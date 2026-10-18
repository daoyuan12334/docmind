package com.docmind.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 递归字符分块。优先级：空行段落 → 中文/英文句子边界 → 硬切。
 * 这样既保留语义完整性，又不会因超长句导致切片爆掉。
 */
@Component
public class TextSplitter {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?；;\\n])");
    private static final Pattern PARAGRAPH = Pattern.compile("\\n\\s*\\n");

    public List<String> split(String text, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String normalized = text.replace("\r\n", "\n").trim();
        for (String paragraph : PARAGRAPH.split(normalized)) {
            mergeOrSplit(paragraph.trim(), chunkSize, overlap, result);
        }
        if (result.isEmpty()) {
            result.addAll(hardSplit(normalized, chunkSize, overlap));
        }
        return result;
    }

    private void mergeOrSplit(String paragraph, int chunkSize, int overlap, List<String> out) {
        if (paragraph.isEmpty()) {
            return;
        }
        if (paragraph.length() <= chunkSize) {
            append(out, paragraph, chunkSize, overlap);
            return;
        }
        // 先按句子切，再把短句拼装到接近 chunkSize
        StringBuilder buffer = new StringBuilder();
        for (String sentence : SENTENCE_END.split(paragraph)) {
            if (sentence == null || sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() > chunkSize) {
                if (buffer.length() > 0) {
                    append(out, buffer.toString(), chunkSize, overlap);
                    buffer.setLength(0);
                }
                out.addAll(hardSplit(sentence, chunkSize, overlap));
                continue;
            }
            if (buffer.length() + sentence.length() > chunkSize) {
                append(out, buffer.toString(), chunkSize, overlap);
                buffer.setLength(0);
            }
            buffer.append(sentence);
        }
        if (buffer.length() > 0) {
            append(out, buffer.toString(), chunkSize, overlap);
        }
    }

    private void append(List<String> out, String text, int chunkSize, int overlap) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!out.isEmpty() && overlap > 0) {
            String prev = out.get(out.size() - 1);
            int from = Math.max(0, prev.length() - overlap);
            String carry = prev.substring(from);
            trimmed = carry + trimmed;
            if (trimmed.length() > chunkSize * 2) {
                // 重叠后仍过长时直接截断，避免单个切片无限膨胀
                trimmed = trimmed.substring(0, chunkSize * 2);
            }
        }
        out.add(trimmed);
    }

    private List<String> hardSplit(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        int step = Math.max(1, size - overlap);
        for (int i = 0; i < text.length(); i += step) {
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return chunks;
    }

    /** 粗估 token 数：中文按 1.5 字符/token，英文按 4 字符/token。 */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long chinese = text.chars().filter(c -> c > 0x2E80).count();
        long others = text.length() - chinese;
        return (int) Math.ceil(chinese / 1.5 + others / 4.0);
    }
}
