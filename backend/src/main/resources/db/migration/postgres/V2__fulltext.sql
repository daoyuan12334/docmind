-- PostgreSQL 专用：全文检索列与 HNSW 向量索引
-- 向量本体由 Spring AI 的 PgVectorStore 管理（vector_store 表），
-- 这里只负责关键词召回通道（tsvector）与模糊匹配（pg_trgm）。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS tsv tsvector;

CREATE OR REPLACE FUNCTION kb_chunk_tsv_refresh() RETURNS trigger AS $$
BEGIN
    NEW.tsv := to_tsvector('simple', coalesce(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_kb_chunk_tsv ON kb_chunk;
CREATE TRIGGER trg_kb_chunk_tsv
    BEFORE INSERT OR UPDATE OF content ON kb_chunk
    FOR EACH ROW EXECUTE FUNCTION kb_chunk_tsv_refresh();

CREATE INDEX IF NOT EXISTS idx_chunk_tsv ON kb_chunk USING gin (tsv);
CREATE INDEX IF NOT EXISTS idx_chunk_content_trgm ON kb_chunk USING gin (content gin_trgm_ops);

-- 已存在数据回填
UPDATE kb_chunk SET tsv = to_tsvector('simple', coalesce(content, '')) WHERE tsv IS NULL;
