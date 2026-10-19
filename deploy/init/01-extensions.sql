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

-- 关于中文全文检索：
-- PostgreSQL 自带的 'simple' / 'english' 词典对中文无能为力 —— 中文没有空格，
-- 整句会被当成一个 token，导致 tsvector 检索在中文上几乎召回不到东西。
-- 所以本项目**不假装**做了中文分词：tsv 列只用于英文/数字场景的关键词召回，
-- 中文查询主要靠下面的 ILIKE 子串匹配通道（见 PgKeywordSearch.search）。
--
-- 若确实需要中文分词检索，须自行安装 pg_jieba 或 zhparser 扩展，
-- 并把 db/migration/postgres/V2__fulltext.sql 里的 'simple' 换成对应配置名。
-- 这两个扩展不在 pgvector 官方镜像里，需要自行构建带扩展的镜像。
