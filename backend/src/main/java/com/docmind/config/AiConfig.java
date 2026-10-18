package com.docmind.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 客户端装配。
 * 这里把 MCP 客户端发现的外部工具挂到业务 ChatClient 上：
 * 启用 spring.ai.mcp.client 后，模型在回答时可以自主调用外部 MCP 工具。
 */
@Configuration
public class AiConfig {

    /**
     * ChatClient：接入 MCP 工具回调，让模型在作答时能主动调用知识库工具。
     *
     * <p>说明：Spring AI 2.0.1 里 Builder 只有已废弃的 defaultToolCallbacks(...) 这一个入口
     * （没有同名的非废弃替代方法），暂时只能用它，等官方给出迁移路径再换。
     */
    @Bean
    @SuppressWarnings({"deprecation", "removal"})
    public ChatClient chatClient(ChatClient.Builder builder,
                                 ObjectProvider<ToolCallbackProvider> mcpTools) {
        ChatClient.Builder customized = builder
                .defaultSystem("你是 DocMind 企业文档助手，回答使用简体中文。");
        mcpTools.ifAvailable(customized::defaultToolCallbacks);
        return customized.build();
    }
}
