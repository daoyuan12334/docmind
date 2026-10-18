-- =============================================================================
-- PostgreSQL 初始化：仅在数据目录为空时执行一次（docker-entrypoint-initdb.d）
-- 建表 / 迁移统一交给 Flyway（backend/src/main/resources/db/migration），
-- 这里只做扩展与非超级用户无法补做的库级对象。
-- =============================================================================

-- 向量检索（kb_chunk.embedding）
CREATE EXTENSION IF NOT EXISTS vector;

-- 中文/模糊关键词检索（pg_trgm + tsvector 混合）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 生成 UUID（MCP / 任务幂等键）
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 不引入中文分词插件也能工作的全文检索配置（配合 pg_trgm 兜底）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'zh_simple') THEN
        CREATE TEXT SEARCH CONFIGURATION zh_simple (PARSER = 'default', COPY = simple);
    END IF;
END$$;
