package com.docmind.observe;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
        registry.gauge("docmind.retrieval.hits", hitCount);
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
