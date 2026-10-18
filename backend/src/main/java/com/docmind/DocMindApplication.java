package com.docmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DocMind —— 私有文档智能系统。
 *
 * <p>能力矩阵：RAG 检索增强、MCP 服务端/客户端、Redis 会话与缓存、Kafka 异步摄取流水线、
 * 多模态理解（文本/图片/扫描件/表格/音频/视频）、Micrometer 全链路可观测。
 */
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class DocMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocMindApplication.class, args);
    }
}
