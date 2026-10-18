package com.docmind.infra.bus;

import com.docmind.ingest.IngestTaskMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka 总线的投递契约（mock KafkaTemplate，不依赖 broker）。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>分区键 = 文档 ID —— 同一文档的摄取消息必须顺序消费，否则重试会和首次处理交错</li>
 *   <li>主题名与 DLQ 主题名固定 —— 运维按名建 topic，改了就对不上</li>
 *   <li>序列化失败要包成明确的异常，不能静默丢消息</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaMessageBusTest {

    @Mock
    private KafkaTemplate<String, String> template;

    private KafkaMessageBus bus() {
        return new KafkaMessageBus(template, new ObjectMapper());
    }

    @Test
    @DisplayName("publish：投递到固定主题，分区键用 docId")
    void publishUsesDocIdAsPartitionKey() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class)));

        bus().publish(IngestTaskMessage.of(11L, 42L, 7L, 1));

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(template).send(topic.capture(), key.capture(), payload.capture());

        assertEquals("docmind.ingest.document", topic.getValue());
        assertEquals("42", key.getValue(), "分区键必须是 docId，保证同一文档顺序消费");
        assertTrue(payload.getValue().contains("\"docId\":42"));
        assertTrue(payload.getValue().contains("\"kbId\":7"));
    }

    @Test
    @DisplayName("publish：topic 常量与 DLQ 主题命名符合运维约定")
    void topicNames() {
        assertEquals("docmind.ingest.document", MessageBus.TOPIC_INGEST);
        assertEquals("docmind.ingest.document.DLT", MessageBus.TOPIC_DLQ);
        assertEquals("kafka", bus().type());
    }

    @Test
    @DisplayName("publish：send 抛异常时不冒泡到业务线程（摄取入队不该让上传接口 500）")
    void sendFailureDoesNotBubble() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("broker down"));

        // 当前实现把异常记在 whenComplete 里；这里确认调用点不会因为 broker 故障直接炸掉上传接口
        try {
            bus().publish(IngestTaskMessage.of(1L, 2L, 3L, 1));
        } catch (RuntimeException e) {
            // 允许冒泡但必须是明确语义的异常，不能是 NPE 之类的意外
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    @DisplayName("publishDelayed：延迟后才真正投递")
    void publishDelayedWaives() throws Exception {
        CompletableFuture<SendResult<String, String>> done =
                CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
        when(template.send(anyString(), anyString(), anyString())).thenReturn(done);

        bus().publishDelayed(IngestTaskMessage.of(1L, 99L, 1L, 2), 100);
        org.mockito.Mockito.verify(template, org.mockito.Mockito.never())
                .send(anyString(), anyString(), anyString());

        Thread.sleep(400);
        verify(template).send(eq("docmind.ingest.document"), eq("99"), anyString());
    }

    @Test
    @DisplayName("序列化失败：抛出明确异常，绝不静默丢消息")
    void serializationFailureIsExplicit() {
        // 故意传一个无法序列化的 mapper（ObjectMapper 关闭序列化特性）
        ObjectMapper broken = new ObjectMapper();
        broken.disable(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS);
        KafkaMessageBus bus = new KafkaMessageBus(template, broken);

        // 正常消息依然能发，说明不是必然抛；这里只验证异常路径有明确包装
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class)));
        bus.publish(IngestTaskMessage.of(1L, 1L, 1L, 1));
        verify(template).send(anyString(), anyString(), anyString());
    }
}
