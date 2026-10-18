package com.docmind.ingest;

import com.docmind.config.AppProperties;
import com.docmind.infra.bus.IngestTaskConsumer;
import com.docmind.infra.bus.MessageBus;
import com.docmind.infra.lock.LockPort;
import com.docmind.infra.storage.StoragePort;
import com.docmind.kb.ChunkRepository;
import com.docmind.kb.DocumentRepository;
import com.docmind.kb.KbModels;
import com.docmind.kb.KnowledgeBaseRepository;
import com.docmind.ingest.multimodal.MultimodalService;
import com.docmind.ingest.parser.DocumentParserRegistry;
import com.docmind.observe.MetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 摄取流水线执行器。业务上串起：解析 → 分块 → 向量化 → 索引 → 摘要。
 * 可重入：重复消费同一文档时先清理旧切片与向量，保证最终一致。
 * 失败按指数退避重试，超过上限落死信并标记文档 FAILED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestWorker implements IngestTaskConsumer {

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final KnowledgeBaseRepository knowledgeBases;
    private final IngestTaskRepository tasks;
    private final StoragePort storage;
    private final DocumentParserRegistry registry;
    private final VectorIndexer vectorIndexer;
    private final TextSplitter splitter;
    private final MultimodalService multimodal;
    private final MetricsService metrics;
    private final LockPort lock;
    private final AppProperties props;
    private final ObjectProvider<MessageBus> busProvider;

    @Override
    public void consume(IngestTaskMessage message) {
        String lockKey = "doc:" + message.docId();
        if (!lock.tryLock(lockKey, Duration.ofMinutes(10))) {
            log.warn("文档正在处理中，跳过本次消息 docId={}", message.docId());
            return;
        }
        long start = System.currentTimeMillis();
        tasks.start(message.taskId(), message.attempt());
        KbModels.Document doc = documents.findById(message.docId()).orElse(null);
        if (doc == null) {
            log.warn("文档不存在，丢弃消息 docId={}", message.docId());
            lock.unlock(lockKey);
            return;
        }
        try {
            runPipeline(message, doc);
            long cost = System.currentTimeMillis() - start;
            tasks.success(message.taskId(), cost);
            metrics.recordIngest("PIPELINE", "SUCCESS", cost);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("摄取失败 docId={} attempt={}", message.docId(), message.attempt(), e);
            handleFailure(message, doc, e, cost);
        } finally {
            lock.unlock(lockKey);
        }
    }

    private void runPipeline(IngestTaskMessage message, KbModels.Document doc) throws Exception {
        KbModels.KnowledgeBase kb = knowledgeBases.findById(doc.kbId())
                .orElseThrow(() -> new IllegalStateException("知识库不存在: " + doc.kbId()));
        int chunkSize = kb.chunkSize() > 0 ? kb.chunkSize() : props.getIngest().getChunkSize();
        int overlap = Math.min(kb.chunkOverlap(), chunkSize / 2);

        // 1) 解析
        documents.updateStatus(doc.id(), KbModels.DocStatus.PARSING, "PARSE", 10, null);
        tasks.stage(message.taskId(), "PARSE");
        byte[] bytes = storage.get(doc.storageKey()).readAllBytes();
        var parser = registry.select(doc.modality(), doc.ext());
        ParsedDocument parsed = parser.parse(doc, bytes);

        // 2) 分块
        documents.updateStatus(doc.id(), KbModels.DocStatus.CHUNKING, "CHUNK", 40, null);
        tasks.stage(message.taskId(), "CHUNK");
        List<ChunkRepository.ChunkDraft> drafts = buildChunks(parsed, doc, chunkSize, overlap);
        if (drafts.isEmpty()) {
            throw new IllegalStateException("未解析出任何文本内容");
        }

        // 3) 清理旧数据（支持重复消费/重新索引）
        vectorIndexer.deleteByDoc(doc.id());
        chunks.deleteByDoc(doc.id());

        // 4) 落库 + 向量化
        documents.updateStatus(doc.id(), KbModels.DocStatus.EMBEDDING, "EMBED", 70, null);
        tasks.stage(message.taskId(), "EMBED");
        List<Long> chunkIds = chunks.insertBatch(drafts);
        List<String> vectorIds = vectorIndexer.index(doc.kbId(), doc.id(), doc.name(), drafts, chunkIds);
        chunks.updateVectorIds(chunkIds, vectorIds);

        // 5) 摘要 + 收尾
        tasks.stage(message.taskId(), "SUMMARY");
        String summary = multimodal.summarize(parsed.allText(), 6000);
        documents.finishSuccess(doc.id(), parsed.pageCount(), drafts.size(),
                drafts.stream().mapToInt(ChunkRepository.ChunkDraft::tokenCount).sum(),
                summary, parsed.modality() == null ? doc.modality() : parsed.modality());
        knowledgeBases.recount(doc.kbId());
        log.info("摄取完成 docId={} chunks={} pages={}", doc.id(), drafts.size(), parsed.pageCount());
    }

    private List<ChunkRepository.ChunkDraft> buildChunks(ParsedDocument parsed, KbModels.Document doc,
                                                          int chunkSize, int overlap) {
        List<ChunkRepository.ChunkDraft> drafts = new ArrayList<>();
        int index = 0;
        for (ParsedDocument.Block block : parsed.blocks()) {
            // 表格与图片描述整块保留，避免切碎后语义丢失
            boolean keepWhole = block.type() == ParsedDocument.BlockType.TABLE
                    || block.type() == ParsedDocument.BlockType.IMAGE_CAPTION;
            List<String> pieces = keepWhole && block.text().length() <= chunkSize * 2
                    ? List.of(block.text())
                    : splitter.split(block.text(), chunkSize, overlap);
            for (String piece : pieces) {
                drafts.add(new ChunkRepository.ChunkDraft(doc.id(), doc.kbId(), index++,
                        piece, sha256(piece), splitter.estimateTokens(piece),
                        block.pageNo(), block.type().name(), block.meta()));
            }
        }
        return drafts;
    }

    private void handleFailure(IngestTaskMessage message, KbModels.Document doc, Exception e, long cost) {
        String error = String.valueOf(e.getMessage());
        int maxAttempts = Math.max(1, props.getIngest().getMaxAttempts());
        if (message.attempt() < maxAttempts) {
            tasks.retrying(message.taskId(), error, cost);
            metrics.recordIngest("PIPELINE", "RETRYING", cost);
            long delay = props.getIngest().getRetryBaseDelayMillis() * (long) Math.pow(2, message.attempt() - 1);
            MessageBus bus = busProvider.getIfAvailable();
            if (bus != null) {
                bus.publishDelayed(message.nextAttempt(), delay);
                log.info("安排重试 docId={} attempt={} delay={}ms", doc.id(), message.attempt() + 1, delay);
            }
        } else {
            tasks.dead(message.taskId(), error, cost);
            metrics.recordIngest("PIPELINE", "FAILED", cost);
            documents.markFailed(doc.id(), error);
        }
    }

    @Override
    public void handleDeadLetter(IngestTaskMessage message, String error) {
        tasks.dead(message.taskId(), error, 0);
        documents.markFailed(message.docId(), error);
        metrics.recordIngest("PIPELINE", "DEAD", 0);
        log.error("任务进入死信 docId={} taskId={}", message.docId(), message.taskId());
    }

    public static String sha256Bytes(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
