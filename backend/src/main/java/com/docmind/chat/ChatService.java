package com.docmind.chat;

import com.docmind.common.TraceContext;
import com.docmind.kb.KbAccess;
import com.docmind.observe.MetricsService;
import com.docmind.rag.RagModels;
import com.docmind.rag.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 对话服务。SSE 事件分三段：meta（引用与会话信息）→ delta（增量正文）→ done（结束）。
 * 前端据此先渲染来源，再逐字输出正文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final RagService ragService;
    private final ChatRepository repository;
    private final ChatMemoryService memory;
    private final QuotaService quota;
    private final KbAccess access;
    private final MetricsService metrics;
    private final ObjectMapper mapper;

    public Flux<ServerSentEvent<String>> stream(Long kbId, Long conversationId, String question,
                                                Integer topK, long userId) {
        access.requireKb(kbId, userId);
        quota.checkRequest(userId);
        quota.checkTokenBudget(userId);

        long convId = conversationId == null || conversationId <= 0
                ? repository.createConversation(userId, kbId, titleOf(question))
                : conversationId;
        if (conversationId != null && conversationId > 0) {
            repository.ensureOwner(userId, convId);
        }
        repository.addMessage(convId, "user", question, null, null, 0, 0, 0, null);
        memory.append(convId, "user", question);

        List<RagModels.RetrievedChunk> chunks = ragService.retrieve(kbId, question, topK);
        StringBuilder accumulator = new StringBuilder();
        long traceId = 0;
        String meta = metaEvent(convId, chunks);

        Flux<ServerSentEvent<String>> head = Flux.just(
                ServerSentEvent.<String>builder().event("meta").data(meta).build());

        Flux<ServerSentEvent<String>> body = ragService.stream(kbId, question, memory.load(convId), topK, userId, convId)
                .map(token -> {
                    accumulator.append(token);
                    return ServerSentEvent.<String>builder().event("delta").data(token).build();
                })
                .onErrorResume(e -> {
                    log.error("流式生成失败 convId={}", convId, e);
                    return Flux.just(ServerSentEvent.<String>builder().event("error")
                            .data(String.valueOf(e.getMessage())).build());
                });

        Flux<ServerSentEvent<String>> tail = Flux.defer(() -> {
            String answer = accumulator.toString();
            List<RagModels.Citation> citations = ragService.buildCitations(answer, chunks);
            String citationJson = toJson(citations);
            repository.addMessage(convId, "assistant", answer, citationJson, null, 0, 0, 0, null);
            memory.append(convId, "assistant", answer);
            metrics.recordChat("chat-stream", "stream", 0, 0, 0, answer.length() > 0);
            return Flux.just(ServerSentEvent.<String>builder().event("done")
                    .data(toJson(Map.of("conversationId", convId, "citations", citations.size()))).build());
        });

        return Flux.concat(head, body, tail);
    }

    /** 同步问答：用于调试、批量评测与 MCP 工具调用。 */
    public RagModels.RagAnswer ask(Long kbId, Long conversationId, String question, Integer topK, long userId) {
        access.requireKb(kbId, userId);
        quota.checkRequest(userId);
        quota.checkTokenBudget(userId);
        long convId = conversationId == null || conversationId <= 0
                ? repository.createConversation(userId, kbId, titleOf(question))
                : conversationId;
        repository.addMessage(convId, "user", question, null, null, 0, 0, 0, null);
        memory.append(convId, "user", question);
        RagModels.RagAnswer answer = ragService.answer(kbId, question, memory.load(convId), topK, userId, convId);
        repository.addMessage(convId, "assistant", answer.answer(), toJson(answer.citations()), null,
                answer.promptTokens(), answer.completionTokens(), answer.latencyMs(), answer.model());
        memory.append(convId, "assistant", answer.answer());
        return answer;
    }

    private String metaEvent(long convId, List<RagModels.RetrievedChunk> chunks) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "conversationId", convId,
                    "traceId", String.valueOf(TraceContext.current()),
                    "sources", chunks.stream()
                            .map(c -> Map.of(
                                    "docId", c.docId(),
                                    "docName", c.docName(),
                                    "pageNo", c.pageNo() == null ? 0 : c.pageNo(),
                                    "score", c.score()))
                            .toList()));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String titleOf(String question) {
        String q = question == null ? "" : question.trim();
        return q.length() <= 30 ? q : q.substring(0, 30) + "…";
    }
}
