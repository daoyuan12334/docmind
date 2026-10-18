package com.docmind.infra.bus;

import com.docmind.ingest.IngestTaskMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Kafka 总线。文档 ID 作为分区键，保证同一文档的消息顺序消费。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "docmind.bus.type", havingValue = "kafka")
public class KafkaMessageBus implements MessageBus {

    private final KafkaTemplate<String, String> template;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kafka-delay-publisher");
                t.setDaemon(true);
                return t;
            });

    public KafkaMessageBus(KafkaTemplate<String, String> template, ObjectMapper mapper) {
        this.template = template;
        this.mapper = mapper;
    }

    @Override
    public void publish(IngestTaskMessage message) {
        try {
            String payload = mapper.writeValueAsString(message);
            template.send(TOPIC_INGEST, String.valueOf(message.docId()), payload)
                    .whenComplete((res, ex) -> {
                        if (ex != null) {
                            log.error("投递摄取消息失败 docId={}", message.docId(), ex);
                        }
                    });
        } catch (Exception e) {
            throw new IllegalStateException("序列化摄取消息失败", e);
        }
    }

    @Override
    public void publishDelayed(IngestTaskMessage message, long delayMillis) {
        scheduler.schedule(() -> publish(message), delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public String type() {
        return "kafka";
    }
}
