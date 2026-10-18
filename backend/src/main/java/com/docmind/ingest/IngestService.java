package com.docmind.ingest;

import com.docmind.common.BizException;
import com.docmind.common.ErrorCode;
import com.docmind.infra.bus.MessageBus;
import com.docmind.infra.storage.StoragePort;
import com.docmind.kb.ChunkRepository;
import com.docmind.kb.DocumentRepository;
import com.docmind.kb.KbAccess;
import com.docmind.kb.KbModels;
import com.docmind.kb.KnowledgeBaseRepository;
import com.docmind.ingest.parser.DocumentParserRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 文档上传与任务派发。上传只做落盘 + 建任务，重活全部交给异步流水线。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DocumentRepository documents;
    private final KnowledgeBaseRepository knowledgeBases;
    private final IngestTaskRepository tasks;
    private final StoragePort storage;
    private final MessageBus bus;
    private final KbAccess access;
    private final ChunkRepository chunks;
    private final VectorIndexer vectorIndexer;

    public record UploadResult(long docId, long taskId, String name, boolean duplicate) {
    }

    public UploadResult upload(long kbId, MultipartFile file, long userId) {
        KbModels.KnowledgeBase kb = access.requireKb(kbId, userId);
        String original = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String ext = extOf(original);
        KbModels.Modality modality = DocumentParserRegistry.detectModality(ext);

        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            throw new BizException(ErrorCode.INGEST_ERROR, "读取上传文件失败", e);
        }
        if (bytes.length == 0) {
            throw BizException.badRequest("文件内容为空");
        }

        // 幂等：同一知识库里内容相同的文件不重复处理。
        // 只要已有副本「没失败」就命中——否则用户连点两次上传会烧两遍 token、
        // 而且异步流水线还没跑完时旧写法根本拦不住。失败态放行，让用户能靠重新上传来重试。
        String checksum = IngestWorker.sha256Bytes(bytes);
        var existing = documents.findByChecksum(kbId, checksum);
        if (existing.isPresent() && existing.get().status() != KbModels.DocStatus.FAILED) {
            KbModels.Document dup = existing.get();
            log.info("命中去重 docId={} checksum={} status={}", dup.id(), checksum, dup.status());
            return new UploadResult(dup.id(), 0L, original, true);
        }

        String key = "kb/" + kbId + "/" + LocalDate.now().format(DATE) + "/" + UUID.randomUUID() + "." + ext;
        storage.put(key, new java.io.ByteArrayInputStream(bytes), bytes.length,
                file.getContentType() == null ? "application/octet-stream" : file.getContentType());

        long docId = documents.create(kbId, original, ext, file.getContentType(), bytes.length, checksum, key,
                modality, userId);
        long taskId = tasks.create(docId, kbId, com.docmind.common.TraceContext.current());
        bus.publish(IngestTaskMessage.of(taskId, docId, kbId, 1));
        knowledgeBases.bumpCounts(kbId, 1, 0);
        log.info("文档已入队 docId={} kbId={} name={} modality={}", docId, kbId, original, modality);
        return new UploadResult(docId, taskId, original, false);
    }

    public void retry(long docId, long userId) {
        KbModels.Document doc = documents.findById(docId).orElseThrow(() -> BizException.notFound("文档"));
        access.requireKb(doc.kbId(), userId);
        if (doc.status() != KbModels.DocStatus.FAILED && doc.status() != KbModels.DocStatus.PENDING) {
            throw BizException.badRequest("仅失败或待处理的文档可重试");
        }
        long taskId = tasks.create(docId, doc.kbId(), com.docmind.common.TraceContext.current());
        documents.updateStatus(docId, KbModels.DocStatus.PENDING, "QUEUED", 0, null);
        bus.publish(IngestTaskMessage.of(taskId, docId, doc.kbId(), 1));
    }

    public void delete(long docId, long userId) {
        KbModels.Document doc = documents.findById(docId).orElseThrow(() -> BizException.notFound("文档"));
        access.requireKb(doc.kbId(), userId);
        // 先下线向量与切片，再软删文档，避免出现「查得到但看不到内容」的中间态
        documents.updateStatus(docId, KbModels.DocStatus.DELETING, "DELETE", 90, null);
        vectorIndexer.deleteByDoc(docId);
        chunks.deleteByDoc(docId);
        storage.delete(doc.storageKey());
        documents.softDelete(docId);
        knowledgeBases.recount(doc.kbId());
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "bin" : name.substring(dot + 1).toLowerCase();
    }
}
