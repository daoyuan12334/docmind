package com.docmind.infra.bus;

import com.docmind.ingest.IngestTaskMessage;

/** 摄取任务的消费契约：本地总线与 Kafka 监听器都调它。 */
public interface IngestTaskConsumer {

    /** 处理一条摄取任务。实现方自行决定重试与状态流转。 */
    void consume(IngestTaskMessage message);

    /** Kafka 死信队列投递后的收尾：落库标记失败，便于前端展示原因。 */
    void handleDeadLetter(IngestTaskMessage message, String error);
}
