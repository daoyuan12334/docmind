package com.docmind.config;

import com.docmind.infra.bus.MessageBus;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka 消费者容错：指数退避重试 3 次后投递到死信主题，
 * 由 KafkaIngestListener 的 DLQ 监听器落库标记失败。
 */
@Slf4j
@EnableKafka
@Configuration
@ConditionalOnProperty(name = "docmind.bus.type", havingValue = "kafka")
public class KafkaConfig {

    @Bean
    public NewTopic ingestTopic() {
        return new NewTopic(MessageBus.TOPIC_INGEST, 3, (short) 1);
    }

    @Bean
    public NewTopic ingestDlqTopic() {
        return new NewTopic(MessageBus.TOPIC_DLQ, 1, (short) 1);
    }

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(MessageBus.TOPIC_DLQ, -1));
        var backOff = new ExponentialBackOff(2_000L, 2.0);
        backOff.setMaxElapsedTime(60_000L);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setAckAfterHandle(true);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, CommonErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
