package com.docmind.rag;

import com.docmind.kb.ChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 内存关键词召回（dev / 小规模知识库）。
 * 用简化 BM25：对查询词做 bigram 切分（中文场景下比单字更有区分度），
 * 统计词频并按切片长度归一化。
 */
@Component
@ConditionalOnProperty(name = "docmind.search.type", havingValue = "memory", matchIfMissing = true)
@RequiredArgsConstructor
public class InMemoryKeywordSearch implements KeywordSearchPort {

    private static final Pattern NON_TEXT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final ChunkRepository chunks;

    @Override
    public List<Hit> search(long kbId, String query, int topK) {
        Set<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<com.docmind.kb.KbModels.Chunk> all = chunks.listByKb(kbId, 5000, 0);
        List<Hit> hits = new ArrayList<>();
        for (var chunk : all) {
            double score = score(chunk.content(), terms);
            if (score > 0) {
                hits.add(new Hit(chunk.id(), score));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() <= topK ? hits : new ArrayList<>(hits.subList(0, topK));
    }

    private double score(String content, Set<String> terms) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        double total = 0;
        int len = Math.max(1, content.length());
        for (String term : terms) {
            int count = countMatches(lower, term);
            if (count > 0) {
                // 词频 * 长度归一化，避免长切片靠篇幅刷分
                total += (1 + Math.log(count)) * (1.0 / (1 + Math.log(1 + len / 500.0)));
            }
        }
        return total;
    }

    private int countMatches(String text, String term) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(term, from);
            if (idx < 0) {
                break;
            }
            count++;
            from = idx + term.length();
        }
        return count;
    }

    /** 中文按 bigram 切词，英文按单词切分。 */
    public static Set<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> terms = new LinkedHashSet<>();
        String normalized = query.toLowerCase(Locale.ROOT);
        for (String part : NON_TEXT.split(normalized)) {
            if (part.isBlank()) {
                continue;
            }
            if (part.chars().anyMatch(c -> c > 0x2E80)) {
                if (part.length() <= 2) {
                    terms.add(part);
                } else {
                    for (int i = 0; i < part.length() - 1; i++) {
                        terms.add(part.substring(i, i + 2));
                    }
                }
            } else if (part.length() > 1) {
                terms.add(part);
            }
        }
        return terms;
    }
}
