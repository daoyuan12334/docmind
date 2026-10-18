package com.docmind.infra.bus;

import com.docmind.common.TraceContext;
import com.docmind.ingest.IngestTaskMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 本地消息总线（dev）：用虚拟线程执行器模拟异步消费。
 * 语义上等价 Kafka 的「投递即返回」，让上传接口不被解析阻塞。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "docmind.bus.type", havingValue = "local", matchIfMissing = true)
public class LocalMessageBus implements MessageBus {

    private final TaskExecutor taskExecutor;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "local-bus-delay");
                t.setDaemon(true);
                return t;
            });
    /** 延迟注入，避免 Worker → Bus → Worker 的构造期循环依赖。 */
    private final ObjectProvider<IngestTaskConsumer> consumer;

    public LocalMessageBus(@org.springframework.beans.factory.annotation.Qualifier(
            com.docmind.config.AsyncConfig.INGEST_EXECUTOR) TaskExecutor applicationTaskExecutor,
                           ObjectProvider<IngestTaskConsumer> consumer) {
        this.taskExecutor = applicationTaskExecutor;
        this.consumer = consumer;
    }

    @Override
    public void publish(IngestTaskMessage message) {
        taskExecutor.execute(() -> run(message));
    }

    @Override
    public void publishDelayed(IngestTaskMessage message, long delayMillis) {
        scheduler.schedule(() -> taskExecutor.execute(() -> run(message)), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void run(IngestTaskMessage message) {
        TraceContext.start(message.traceId());
        try {
            consumer.getObject().consume(message);
        } catch (Exception e) {
            log.error("本地总线消费失败 taskId={} docId={}", message.taskId(), message.docId(), e);
        } finally {
            TraceContext.clear();
        }
    }

    @Override
    public String type() {
        return "local";
    }
}
