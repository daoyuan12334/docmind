package com.docmind.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 全文检索召回。
 * 中文场景下 tsvector('simple') 分词偏弱，因此叠加 ILIKE 子串匹配作为补充通道，
 * 两路分数取最大值，保证中文查询也能召回。
 */
@Component
@ConditionalOnProperty(name = "docmind.search.type", havingValue = "pg")
@RequiredArgsConstructor
public class PgKeywordSearch implements KeywordSearchPort {

    private final JdbcClient jdbc;

    @Override
    public List<Hit> search(long kbId, String query, int topK) {
        List<Hit> hits = new ArrayList<>();
        try {
            jdbc.sql("SELECT id, ts_rank(tsv, plainto_tsquery('simple', ?)) AS score FROM kb_chunk "
                            + "WHERE kb_id = ? AND tsv @@ plainto_tsquery('simple', ?) "
                            + "ORDER BY score DESC LIMIT ?")
                    .params(query, kbId, query, topK)
                    .query((rs, row) -> new Hit(rs.getLong("id"), rs.getDouble("score")))
                    .list()
                    .forEach(hits::add);
        } catch (Exception e) {
            // tsvector 不可用时静默降级，走下面的子串匹配
        }
        jdbc.sql("SELECT id, 1.0 AS score FROM kb_chunk WHERE kb_id = ? AND content ILIKE ? LIMIT ?")
                .params(kbId, "%" + escapeLike(query) + "%", topK)
                .query((rs, row) -> new Hit(rs.getLong("id"), rs.getDouble("score")))
                .list()
                .forEach(hits::add);
        return hits;
    }

    private static String escapeLike(String s) {
        return s.replace("%", "\\%").replace("_", "\\_");
    }
}
