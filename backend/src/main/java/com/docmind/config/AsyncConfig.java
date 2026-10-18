package com.docmind.config;

import com.docmind.common.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步与线程池配置。
 * 注意：摄取是 CPU/IO 混合负载，且外部模型调用是主要耗时，因此分池隔离：
 * 上传后的解析走 ingest 池，避免拖慢 Web 请求线程。
 */
@Configuration
public class AsyncConfig {

    public static final String INGEST_EXECUTOR = "ingestExecutor";

    @Bean(INGEST_EXECUTOR)
    public ThreadPoolTaskExecutor ingestExecutor(AppProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(new MdcTaskDecorator());
        int n = Math.max(1, props.getIngest().getWorkerConcurrency());
        executor.setCorePoolSize(n);
        executor.setMaxPoolSize(Math.max(n, n * 2));
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
