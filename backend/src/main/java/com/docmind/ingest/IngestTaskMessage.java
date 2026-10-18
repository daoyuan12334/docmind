package com.docmind.ingest;

import java.io.Serializable;

/**
 * 摄取任务消息。经 Kafka 或本地总线传递，因此需要可序列化。
 * attempt 用于重试计数，达到上限后进死信。
 */
public record IngestTaskMessage(long taskId, long docId, long kbId, String traceId, int attempt)
        implements Serializable {

    public static IngestTaskMessage of(long taskId, long docId, long kbId, int attempt) {
        return new IngestTaskMessage(taskId, docId, kbId, com.docmind.common.TraceContext.current(), attempt);
    }

    public IngestTaskMessage nextAttempt() {
        return new IngestTaskMessage(taskId, docId, kbId, traceId, attempt + 1);
    }
}
