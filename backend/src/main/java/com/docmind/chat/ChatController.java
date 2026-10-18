package com.docmind.chat;

import com.docmind.common.ApiResult;
import com.docmind.ingest.multimodal.MultimodalService;
import com.docmind.rag.RagModels;
import com.docmind.rag.RagService;
import com.docmind.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Validated
public class ChatController {

    private final ChatService chatService;
    private final ChatRepository repository;
    private final RagService ragService;
    private final MultimodalService multimodal;

    private static final List<String> AUDIO_EXTS = List.of("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "amr");
    private static final List<String> VIDEO_EXTS = List.of("mp4", "mov", "mkv", "avi", "flv", "wmv", "webm", "m4v");

    public record AskRequest(String question, Long conversationId, Integer topK) {
    }

    /** SSE 流式问答。事件：meta / delta / done / error。 */
    @PostMapping(value = "/{kbId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable Long kbId, @RequestBody AskRequest req) {
        return chatService.stream(kbId, req.conversationId(), req.question(), req.topK(), CurrentUser.id());
    }

    /** 同步问答，便于调试与批量评测。 */
    @PostMapping("/{kbId}/ask")
    public ApiResult<?> ask(@PathVariable Long kbId, @RequestBody AskRequest req) {
        RagModels.RagAnswer answer = chatService.ask(kbId, req.conversationId(), req.question(), req.topK(),
                CurrentUser.id());
        return ApiResult.ok(answer);
    }

    /**
     * 多模态问答：上传图片/音频，先用视觉或语音模型理解内容，
     * 再把理解结果作为问题的一部分进入 RAG 流程。
     */
    @PostMapping(value = "/{kbId}/multimodal", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<?> multimodal(@PathVariable Long kbId,
                                   @RequestPart("question") String question,
                                   @RequestPart("file") MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String ext = ext(filename);
        String understood;
        if (AUDIO_EXTS.contains(ext)) {
            understood = multimodal.transcribeAudio(bytes, ext, question);
        } else if (VIDEO_EXTS.contains(ext)) {
            throw new com.docmind.common.BizException(com.docmind.common.ErrorCode.BAD_REQUEST,
                    "视频请先通过「文档上传」进入知识库，再基于转写内容提问");
        } else {
            String mime = file.getContentType() == null ? guessMime(filename) : file.getContentType();
            understood = multimodal.describeImage(bytes, mime, question);
        }
        String composed = "附件内容理解结果：\n" + understood + "\n\n用户问题：" + question;
        RagModels.RagAnswer answer = chatService.ask(kbId, null, composed, null, CurrentUser.id());
        return ApiResult.ok(Map.of("answer", answer.answer(), "citations", answer.citations(),
                "attachmentUnderstanding", understood));
    }

    @PostMapping("/conversations")
    public ApiResult<?> createConversation(@RequestBody Map<String, Object> body) {
        Long kbId = body.get("kbId") == null ? null : Long.valueOf(String.valueOf(body.get("kbId")));
        String title = String.valueOf(body.getOrDefault("title", "新会话"));
        long id = repository.createConversation(CurrentUser.id(), kbId, title);
        return ApiResult.ok(Map.of("id", id));
    }

    @GetMapping("/conversations")
    public ApiResult<?> listConversations(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> rows = repository.listConversations(CurrentUser.id(), limit);
        return ApiResult.ok(rows);
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResult<?> messages(@PathVariable long id, @RequestParam(defaultValue = "100") int limit) {
        repository.ensureOwner(CurrentUser.id(), id);
        return ApiResult.ok(repository.listMessages(id, limit));
    }

    @DeleteMapping("/conversations/{id}")
    public ApiResult<?> deleteConversation(@PathVariable long id) {
        repository.deleteConversation(CurrentUser.id(), id);
        return ApiResult.ok();
    }

    /** 检索调试：只召回不生成，用于观察切片质量。 */
    @GetMapping("/{kbId}/retrieve")
    public ApiResult<?> retrieve(@PathVariable Long kbId, @RequestParam String q,
                                 @RequestParam(defaultValue = "6") int topK) {
        return ApiResult.ok(ragService.retrieve(kbId, q, topK));
    }

    private static String ext(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "wav" : filename.substring(dot + 1);
    }

    private static String guessMime(String filename) {
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (filename.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }
}
