package com.docmind.infra.bus;

import com.docmind.config.KafkaConfig;
import com.docmind.ingest.IngestTaskMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Kafka 链路的真 broker 集成测试：用 {@code @EmbeddedKafka}（KRaft 模式）在本机起一个真的
 * Kafka，不依赖 Docker，验证「生产 → 真 broker → 真监听器 → 业务消费」这条完整链路。
 *
 * <p>与 {@link KafkaMessageBusTest} 的分工：单测用 mock KafkaTemplate 锁住投递契约（便宜、快）；
 * 这里锁住的是 mock 测不出来的东西——消息真的能跨进程边界到达、JSON 真的能被反序列化回来、
 * 以及失败时真的会进死信主题。
 *
 * <p>为什么命名成 {@code *IT}：它要启动 broker，耗时在几十秒量级，不能挂在每次 {@code mvn test} 上。
 * Surefire 默认只跑 {@code *Test}，所以需要显式执行：
 * <pre>{@code ./tool/mvn.sh -B -ntp test -Dtest=KafkaMessageBusIT}</pre>
 *
 * <p>核心不变量：
 * <ul>
 *   <li>分区键 = docId：同一文档的所有摄取消息落在同一分区，重试才不会和首次处理交错</li>
 *   <li>监听器能把 JSON 反序列化回 {@link IngestTaskMessage}（record + Jackson 的坑在这里暴露）</li>
 *   <li>延迟投递不会提前到达</li>
 *   <li>消费连续失败时，重试耗尽后消息进入 DLQ，且 {@code handleDeadLetter} 被调用</li>
 * </ul>
 */
@SpringBootTest(classes = KafkaMessageBusIT.KafkaTestApp.class, properties = {
        "docmind.bus.type=kafka",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false"
})
@EmbeddedKafka(partitions = 3, topics = {MessageBus.TOPIC_INGEST, MessageBus.TOPIC_DLQ})
@DirtiesContext
class KafkaMessageBusIT {

    @Autowired
    private KafkaMessageBus bus;

    @Autowired
    private RecordingConsumer consumer;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    @DisplayName("publish → 真 broker → 监听器反序列化后交给业务 consumer")
    void publishReachesConsumer() {
        IngestTaskMessage msg = new IngestTaskMessage(7001L, 101L, 1L, "trace-it-1", 0);

        bus.publish(msg);

        awaitUntil(() -> consumer.consumed.stream().anyMatch(m -> m.taskId() == 7001L),
                Duration.ofSeconds(30), "监听器收到 7001 号任务");

        IngestTaskMessage got = consumer.consumed.stream()
                .filter(m -> m.taskId() == 7001L).findFirst().orElseThrow();
        assertEquals(101L, got.docId(), "docId 必须原样传回来");
        assertEquals(1L, got.kbId(), "kbId 必须原样传回来");
        assertEquals(0, got.attempt(), "attempt 必须原样传回来");
        assertEquals("trace-it-1", got.traceId(), "traceId 必须原样传回来");
    }

    @Test
    @DisplayName("分区键必须是 docId：同一文档的消息落同一分区，顺序才有保证")
    void partitionKeyIsDocId() {
        try (Consumer<String, String> raw = rawConsumer("it-partition")) {
            bus.publish(new IngestTaskMessage(7002L, 102L, 1L, "trace-it-2", 0));

            ConsumerRecord<String, String> rec = awaitRecord(raw, "102", Duration.ofSeconds(30));
            assertNotNull(rec, "应能在 broker 上读到 key=102 的记录");
            assertEquals("102", rec.key(), "分区键必须等于 docId");
            assertTrue(rec.value().contains("\"docId\":102"), "消息体应为 JSON 且含 docId");
        }
    }

    @Test
    @DisplayName("publishDelayed：延迟窗口内不投递，窗口之后才到达")
    void publishDelayedIsDeferred() {
        try (Consumer<String, String> raw = rawConsumer("it-delay")) {
            bus.publishDelayed(new IngestTaskMessage(7003L, 103L, 1L, "trace-it-3", 0), 1_500L);

            // 立刻 Poll 一次：延迟任务尚未到点，不该出现 key=103 的记录
            assertFalse(hasKey(raw.poll(Duration.ofMillis(500)), "103"),
                    "延迟消息不应在窗口内投递");

            // 窗口之后必须能收到
            ConsumerRecord<String, String> rec = awaitRecord(raw, "103", Duration.ofSeconds(30));
            assertNotNull(rec, "延迟消息应在窗口后到达");
            assertEquals("103", rec.key());
        }
    }

    @Test
    @DisplayName("消费连续失败：重试耗尽后投递死信主题，handleDeadLetter 被调用")
    void failureEndsInDeadLetter() {
        consumer.failAlways = true;
        try {
            bus.publish(new IngestTaskMessage(7004L, 104L, 1L, "trace-it-4", 0));

            // KafkaConfig 里的 DefaultErrorHandler 是指数退避重试（2s 起步、上限 60s），
            // 重试耗尽后由 DeadLetterPublishingRecoverer 投递到 DLQ，所以这里要给足超时。
            awaitUntil(() -> !consumer.deadLetters.isEmpty(),
                    Duration.ofSeconds(150), "失败消息应进入死信队列");

            var dl = consumer.deadLetters.get(0);
            assertEquals(104L, dl.message().docId(), "死信里应能还原出原始消息");
            assertTrue(consumer.consumed.stream().anyMatch(m -> m.taskId() == 7004L),
                    "死信前至少应被消费过一次（说明确实重试过）");
        } finally {
            consumer.failAlways = false;
        }
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 建一个从头消费的裸消费者，用来直接看 broker 上的记录（分区键、消息体原始形状）。
     *
     * <p>这里刻意<b>不用</b> {@code broker.consumeFromAnEmbeddedTopic()}：它内部会另建一个消费者去算
     * 当前位点，而那套默认配置用的是 {@code IntegerDeserializer}，遇到我们 String 类型的分区键会直接抛
     * {@code RecordDeserializationException}。手动 subscribe + 等分配更可控。
     */
    private Consumer<String, String> rawConsumer(String group) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps(group, "false", broker));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        consumer.subscribe(List.of(MessageBus.TOPIC_INGEST));
        long deadline = System.currentTimeMillis() + 20_000;
        while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(100));
        }
        return consumer;
    }

    private static boolean hasKey(Iterable<ConsumerRecord<String, String>> records, String key) {
        for (ConsumerRecord<String, String> r : records) {
            if (key.equals(r.key())) {
                return true;
            }
        }
        return false;
    }

    private static ConsumerRecord<String, String> awaitRecord(Consumer<String, String> consumer, String key,
                                                              Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                if (key.equals(r.key())) {
                    return r;
                }
            }
        }
        return null;
    }

    private static void awaitUntil(Supplier<Boolean> condition, Duration timeout, String desc) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断：" + desc, e);
            }
        }
        fail("等待超时（" + timeout.toMillis() + "ms）：" + desc);
    }

    /** 记录被消费的消息与死信，替代真实业务实现。 */
    static class RecordingConsumer implements IngestTaskConsumer {

        final List<IngestTaskMessage> consumed = new CopyOnWriteArrayList<>();
        final List<DeadLetter> deadLetters = new CopyOnWriteArrayList<>();
        volatile boolean failAlways = false;

        @Override
        public void consume(IngestTaskMessage message) {
            consumed.add(message);
            if (failAlways) {
                throw new IllegalStateException("模拟业务处理失败");
            }
        }

        @Override
        public void handleDeadLetter(IngestTaskMessage message, String error) {
            deadLetters.add(new DeadLetter(message, error));
        }
    }

    record DeadLetter(IngestTaskMessage message, String error) {
    }

    /**
     * 最小可运行上下文：只装载 Kafka 相关 Bean
     * （{@link KafkaConfig} 负责容错与容器工厂，两个业务类是被测对象），不启动整个应用，
     * 免得为了测消息总线还要拉起数据库和 AI 客户端。
     */
    @Configuration
    @Import({KafkaConfig.class, KafkaMessageBus.class, KafkaIngestListener.class})
    static class KafkaTestApp {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ConsumerFactory<String, String> consumerFactory(EmbeddedKafkaBroker broker) {
            Map<String, Object> props = new HashMap<>(
                    KafkaTestUtils.consumerProps("it-app", "false", broker));
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            // KafkaTestUtils 给的默认值不保证是 String 反序列化器，这里显式钉死，
            // 免得哪天换版本悄悄变了、测试报一堆看不懂的序列化错。
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            return new DefaultKafkaConsumerFactory<>(props);
        }

        @Bean
        ProducerFactory<String, String> producerFactory(EmbeddedKafkaBroker broker) {
            Map<String, Object> props = new HashMap<>(KafkaTestUtils.producerProps(broker));
            // 坑：KafkaTestUtils.producerProps() 默认把 key 序列化器设成 IntegerSerializer，
            // 而总线发的是 String.valueOf(docId)，两边对不上会直接抛 SerializationException。
            // 生产环境走 Boot 自动配置（默认 StringSerializer）没问题，这里必须显式覆盖。
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            return new DefaultKafkaProducerFactory<>(props);
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
            return new KafkaTemplate<>(producerFactory);
        }

        @Bean
        RecordingConsumer recordingConsumer() {
            return new RecordingConsumer();
        }
    }
}
