package com.docmind.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docmind.infra.cache.CachePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆。生产存 Redis（多实例共享 + 自动过期），开发存进程内缓存。
 * 只保留最近 N 轮，避免上下文膨胀导致 token 成本失控。
 */
@Service
@RequiredArgsConstructor
public class ChatMemoryService {

    private static final int MAX_TURNS = 8;
    private static final Duration TTL = Duration.ofHours(12);

    private final CachePort cache;
    private final ObjectMapper mapper;

    public record Turn(String role, String content) {
    }

    public void append(long conversationId, String role, String content) {
        String key = key(conversationId);
        try {
            String json = mapper.writeValueAsString(new Turn(role, content));
            cache.append(key, json, MAX_TURNS * 2 + 1, TTL);
        } catch (Exception e) {
            throw new IllegalStateException("会话记忆写入失败", e);
        }
    }

    public List<org.springframework.ai.chat.messages.Message> load(long conversationId) {
        List<String> raw = cache.tail(key(conversationId), MAX_TURNS * 2);
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        for (String item : raw) {
            try {
                Turn turn = mapper.readValue(item, Turn.class);
                if ("user".equals(turn.role())) {
                    messages.add(new org.springframework.ai.chat.messages.UserMessage(turn.content()));
                } else {
                    messages.add(new org.springframework.ai.chat.messages.AssistantMessage(turn.content()));
                }
            } catch (Exception ignored) {
            }
        }
        return messages;
    }

    public void clear(long conversationId) {
        cache.deleteList(key(conversationId));
    }

    private String key(long conversationId) {
        return "docmind:chat:mem:" + conversationId;
    }
}
