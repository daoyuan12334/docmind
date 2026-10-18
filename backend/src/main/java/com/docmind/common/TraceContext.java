package com.docmind.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TraceId 上下文。写入 SLF4J MDC，让每条日志都自带链路 ID；
 * 跨线程池执行时由 MdcTaskDecorator 自动传递。
 */
public final class TraceContext {

    private static final Logger log = LoggerFactory.getLogger(TraceContext.class);
    public static final String MDC_KEY = "traceId";

    private TraceContext() {
    }

    /** 以调用方传入的 ID 为准，否则生成 16 位短 ID。返回实际使用的 ID。 */
    public static String start(String traceId) {
        String id = (traceId == null || traceId.isBlank()) ? newTraceId() : traceId.trim();
        org.slf4j.MDC.put(MDC_KEY, id);
        return id;
    }

    public static String current() {
        return org.slf4j.MDC.get(MDC_KEY);
    }

    public static void clear() {
        org.slf4j.MDC.remove(MDC_KEY);
    }

    public static String newTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
