package com.docmind.infra.bus;

import com.docmind.ingest.IngestTaskMessage;

/**
 * 消息总线。生产走 Kafka（削峰 + 可重放），开发走内存执行器。
 * 业务代码只依赖该接口，切换中间件不需要改动调用方。
 */
public interface MessageBus {

    String TOPIC_INGEST = "docmind.ingest.document";
    String TOPIC_DLQ = "docmind.ingest.document.DLT";

    void publish(IngestTaskMessage message);

    /** 延迟投递（重试退避用）。Kafka 场景下由独立的调度线程触发。 */
    void publishDelayed(IngestTaskMessage message, long delayMillis);

    String type();
}
