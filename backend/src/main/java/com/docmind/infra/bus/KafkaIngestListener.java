package com.docmind.infra.bus;

import com.docmind.common.TraceContext;
import com.docmind.ingest.IngestTaskMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Kafka 摄取消费者。手动 ack：只有业务处理成功才提交位点，
 * 异常交给 KafkaConfig 里的 DefaultErrorHandler 做指数退避重试与死信投递。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "docmind.bus.type", havingValue = "kafka")
public class KafkaIngestListener {

    private final IngestTaskConsumer consumer;
    private final ObjectMapper objectMapper;

    public KafkaIngestListener(IngestTaskConsumer consumer, ObjectMapper objectMapper) {
        this.consumer = consumer;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaMessageBus.TOPIC_INGEST, groupId = "docmind-ingest")
    public void onMessage(String payload, Acknowledgment ack) throws Exception {
        IngestTaskMessage message = objectMapper.readValue(payload, IngestTaskMessage.class);
        TraceContext.start(message.traceId());
        try {
            consumer.consume(message);
            ack.acknowledge();
        } finally {
            TraceContext.clear();
        }
    }

    @KafkaListener(topics = KafkaMessageBus.TOPIC_DLQ, groupId = "docmind-ingest-dlq")
    public void onDeadLetter(String payload, @Header(name = "kafka_dlt-exception-message", required = false) String error,
                             Acknowledgment ack) {
        try {
            IngestTaskMessage message = objectMapper.readValue(payload, IngestTaskMessage.class);
            log.error("收到死信 docId={} taskId={} cause={}", message.docId(), message.taskId(), error);
            consumer.handleDeadLetter(message, error);
        } catch (Exception e) {
            log.error("死信处理失败 payload={}", payload, e);
        } finally {
            ack.acknowledge();
        }
    }
}
