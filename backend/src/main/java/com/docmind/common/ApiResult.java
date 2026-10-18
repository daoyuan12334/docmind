package com.docmind.common;

import java.io.Serializable;

/** 统一响应包装。 */
public record ApiResult<T>(int code, String message, T data, String traceId) implements Serializable {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(0, "ok", data, TraceContext.current());
    }

    public static <T> ApiResult<T> ok() {
        return new ApiResult<>(0, "ok", null, TraceContext.current());
    }

    public static <T> ApiResult<T> fail(String message) {
        return new ApiResult<>(-1, message, null, TraceContext.current());
    }
}
