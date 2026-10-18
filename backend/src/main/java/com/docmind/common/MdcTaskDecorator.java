package com.docmind.common;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/** 把主线程的 MDC（traceId 等）带到异步线程，保证跨线程池的日志仍能串起一条链路。 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                } else {
                    TraceContext.start(null);
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
