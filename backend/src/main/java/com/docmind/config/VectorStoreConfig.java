package com.docmind.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.File;

/**
 * 向量库装配：
 * - prod（pgvector）由 spring-ai-starter-vector-store-pgvector 自动配置；
 * - dev/单机用 SimpleVectorStore，并周期性落盘，避免重启后重新向量化。
 */
@Slf4j
@Configuration
public class VectorStoreConfig {

    private volatile SimpleVectorStore memoryStore;

    @Bean
    @ConditionalOnProperty(name = "docmind.vector.type", havingValue = "memory", matchIfMissing = true)
    public VectorStore vectorStore(EmbeddingModel embeddingModel, AppProperties props) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File file = new File(props.getVector().getFile());
        if (file.exists()) {
            try {
                store.load(file);
                log.info("已载入本地向量库：{}", file.getAbsolutePath());
            } catch (Exception e) {
                log.warn("本地向量库载入失败，将以空库启动", e);
            }
        }
        this.memoryStore = store;
        return store;
    }

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void persist() {
        SimpleVectorStore store = this.memoryStore;
        if (store == null) {
            return;
        }
        try {
            store.save(new File("./data/vector-store.json"));
        } catch (Exception e) {
            log.warn("向量库落盘失败", e);
        }
    }
}
