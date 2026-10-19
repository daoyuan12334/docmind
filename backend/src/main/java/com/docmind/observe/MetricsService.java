package com.docmind.observe;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标。Micrometer 采集 → Prometheus 抓取 → Grafana 展示。
 * 关注的四件事：模型调用量、Token 成本、各环节耗时、检索召回质量。
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final MeterRegistry registry;
    private final AtomicLong ingestQueueDepth = new AtomicLong();
    private final AtomicLong retrievalHits = new AtomicLong();

    /**
     * 启动即注册所有业务 meter（零值占位）。
     *
     * 为什么必须有这一步：Micrometer 的 Counter / Timer 是**首次 record 时才创建**的。
     * 不预热的话，服务刚起来、还没人调用的时候：
     *   1. /actuator/prometheus 里一条业务指标都没有；
     *   2. Prometheus 里基于 rate(docmind_ai_requests_total[5m]) 的告警规则
     *      会因为「序列不存在」而**静默失效** —— 不报错，但也不告警，最难发现；
     *   3. Grafana 面板显示 No data，看着像监控坏了不像"还没数据"。
     *
     * 预热之后指标 family 稳定存在（值为 0），上面的问题全部消失。
     * 注意：这里只需要保证每个 meter family 至少有一个 tag 组合被注册，
     * 真实调用再按自己的 scene/model/tool 维度增量。
     */
    @PostConstruct
    public void warmUpMeters() {
        Counter.builder("docmind.ai.requests")
                .tag("scene", "warmup").tag("model", "none").tag("success", "true")
                .register(registry);
        Counter.builder("docmind.ai.tokens")
                .tag("scene", "warmup").tag("model", "none").tag("type", "prompt")
                .register(registry);
        Counter.builder("docmind.ai.tokens")
                .tag("scene", "warmup").tag("model", "none").tag("type", "completion")
                .register(registry);
        Timer.builder("docmind.ai.duration")
                .tag("scene", "warmup").tag("model", "none")
                .publishPercentileHistogram().register(registry);

        Counter.builder("docmind.ingest.documents")
                .tag("status", "none").register(registry);
        Timer.builder("docmind.ingest.duration")
                .tag("stage", "warmup").tag("status", "none")
                .publishPercentileHistogram().register(registry);
        registry.gauge("docmind.ingest.queue", ingestQueueDepth);

        Timer.builder("docmind.retrieval.duration")
                .publishPercentileHistogram().register(registry);
        registry.gauge("docmind.retrieval.hits", retrievalHits);

        Counter.builder("docmind.mcp.calls")
                .tag("tool", "warmup").tag("success", "true")
                .register(registry);
    }

    public void recordChat(String scene, String model, int promptTokens, int completionTokens,
                           long latencyMillis, boolean success) {
        Counter.builder("docmind.ai.requests")
                .tag("scene", scene)
                .tag("model", model == null ? "unknown" : model)
                .tag("success", String.valueOf(success))
                .register(registry)
                .increment();
        Counter.builder("docmind.ai.tokens")
                .tag("scene", scene)
                .tag("model", model == null ? "unknown" : model)
                .tag("type", "prompt")
                .register(registry)
                .increment(promptTokens);
        Counter.builder("docmind.ai.tokens")
                .tag("scene", scene)
                .tag("model", model == null ? "unknown" : model)
                .tag("type", "completion")
                .register(registry)
                .increment(completionTokens);
        Timer.builder("docmind.ai.duration")
                .tag("scene", scene)
                .tag("model", model == null ? "unknown" : model)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(latencyMillis));
    }

    public void recordIngest(String stage, String status, long millis) {
        Timer.builder("docmind.ingest.duration")
                .tag("stage", stage)
                .tag("status", status)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(millis));
        Counter.builder("docmind.ingest.documents")
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void recordRetrieval(int hitCount, long millis) {
        Timer.builder("docmind.retrieval.duration")
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(millis));
        // 必须是可变容器：直接传 int 会装箱成不可变 Integer，Gauge 每次读到的
        // 都是旧引用上的值，永远停在第一次的结果上。
        retrievalHits.set(hitCount);
    }

    public void queueDepth(long depth) {
        ingestQueueDepth.set(depth);
        registry.gauge("docmind.ingest.queue", ingestQueueDepth);
    }

    public void recordMcpCall(String tool, boolean success) {
        Counter.builder("docmind.mcp.calls")
                .tag("tool", tool)
                .tag("success", String.valueOf(success))
                .register(registry)
                .increment();
    }
}
