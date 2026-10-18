package com.docmind.common;

import java.time.Instant;

/** 统一错误响应体。 */
public record ErrorResponse(String code, String message, String traceId) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, TraceContext.current());
    }
}
