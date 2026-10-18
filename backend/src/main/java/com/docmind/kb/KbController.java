package com.docmind.kb;

import com.docmind.common.ApiResult;
import com.docmind.common.BizException;
import com.docmind.ingest.IngestService;
import com.docmind.kb.KbModels.KnowledgeBase;
import com.docmind.ingest.IngestTaskRepository;
import com.docmind.observe.MetricsService;
import com.docmind.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** 知识库与文档管理。 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
@Validated
public class KbController {

    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final IngestTaskRepository tasks;
    private final IngestService ingestService;
    private final KbAccess access;
    private final MetricsService metrics;

    public record CreateRequest(String name, String description, String visibility,
                                Integer chunkSize, Integer chunkOverlap, Integer topK) {
    }

    @PostMapping
    public ApiResult<?> create(@RequestBody CreateRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw BizException.badRequest("知识库名称不能为空");
        }
        long id = knowledgeBases.create(req.name(), req.description(), CurrentUser.id(),
                req.visibility() == null ? "PRIVATE" : req.visibility(),
                "text-embedding-v4",
                req.chunkSize() == null ? 800 : req.chunkSize(),
                req.chunkOverlap() == null ? 120 : req.chunkOverlap(),
                req.topK() == null ? 6 : req.topK());
        return ApiResult.ok(Map.of("id", id));
    }

    @GetMapping
    public ApiResult<?> list() {
        return ApiResult.ok(knowledgeBases.listVisible(CurrentUser.id(), CurrentUser.isAdmin()));
    }

    @GetMapping("/{id}")
    public ApiResult<?> detail(@PathVariable long id) {
        return ApiResult.ok(access.requireKb(id, CurrentUser.id()));
    }

    @PutMapping("/{id}")
    public ApiResult<?> update(@PathVariable long id, @RequestBody CreateRequest req) {
        KnowledgeBase kb = access.requireOwner(id, CurrentUser.id());
        knowledgeBases.update(id,
                req.name() == null ? kb.name() : req.name(),
                req.description() == null ? kb.description() : req.description(),
                req.visibility() == null ? kb.visibility() : req.visibility(),
                req.chunkSize() == null ? kb.chunkSize() : req.chunkSize(),
                req.chunkOverlap() == null ? kb.chunkOverlap() : req.chunkOverlap(),
                req.topK() == null ? kb.topK() : req.topK());
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<?> delete(@PathVariable long id) {
        access.requireOwner(id, CurrentUser.id());
        for (KbModels.Document doc : documents.listByKb(id)) {
            ingestService.delete(doc.id(), CurrentUser.id());
        }
        knowledgeBases.softDelete(id);
        return ApiResult.ok();
    }

    @GetMapping("/{id}/documents")
    public ApiResult<?> documents(@PathVariable long id) {
        access.requireKb(id, CurrentUser.id());
        return ApiResult.ok(documents.listByKb(id));
    }

    /** 上传文档。返回 docId / taskId，前端用轮询或 SSE 跟踪处理进度。 */
    @PostMapping(value = "/{id}/documents", consumes = "multipart/form-data")
    public ApiResult<?> upload(@PathVariable long id,
                               @RequestPart("file") MultipartFile file) {
        return ApiResult.ok(ingestService.upload(id, file, CurrentUser.id()));
    }

    @GetMapping("/documents/{docId}")
    public ApiResult<?> document(@PathVariable long docId) {
        KbModels.Document doc = documents.findById(docId).orElseThrow(() -> BizException.notFound("文档"));
        access.requireKb(doc.kbId(), CurrentUser.id());
        return ApiResult.ok(doc);
    }

    @GetMapping("/documents/{docId}/chunks")
    public ApiResult<?> chunks(@PathVariable long docId,
                               @RequestParam(defaultValue = "100") int limit) {
        KbModels.Document doc = documents.findById(docId).orElseThrow(() -> BizException.notFound("文档"));
        access.requireKb(doc.kbId(), CurrentUser.id());
        List<KbModels.Chunk> rows = chunks.listByDoc(docId);
        return ApiResult.ok(rows.size() <= limit ? rows : rows.subList(0, limit));
    }

    @GetMapping("/documents/{docId}/tasks")
    public ApiResult<?> tasks(@PathVariable long docId,
                              @RequestParam(defaultValue = "10") int limit) {
        KbModels.Document doc = documents.findById(docId).orElseThrow(() -> BizException.notFound("文档"));
        access.requireKb(doc.kbId(), CurrentUser.id());
        return ApiResult.ok(tasks.listByDoc(docId, limit));
    }

    @PostMapping("/documents/{docId}/retry")
    public ApiResult<?> retry(@PathVariable long docId) {
        ingestService.retry(docId, CurrentUser.id());
        return ApiResult.ok();
    }

    @DeleteMapping("/documents/{docId}")
    public ApiResult<?> deleteDocument(@PathVariable long docId) {
        ingestService.delete(docId, CurrentUser.id());
        return ApiResult.ok();
    }

    /** 队列状态：给运维看板用。 */
    @GetMapping("/queue")
    public ApiResult<?> queue() {
        long running = tasks.countRunning();
        metrics.queueDepth(running);
        return ApiResult.ok(Map.of("running", running));
    }
}
