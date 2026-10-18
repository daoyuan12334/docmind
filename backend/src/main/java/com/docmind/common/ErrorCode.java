package com.docmind.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST.value(), "请求参数不合法"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED.value(), "未认证或令牌已过期"),
    FORBIDDEN(HttpStatus.FORBIDDEN.value(), "无权限"),
    NOT_FOUND(HttpStatus.NOT_FOUND.value(), "资源不存在"),
    CONFLICT(HttpStatus.CONFLICT.value(), "资源冲突"),
    QUOTA_EXCEEDED(429, "超出配额或触发限流"),
    AI_ERROR(HttpStatus.BAD_GATEWAY.value(), "模型服务调用失败"),
    STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR.value(), "存储服务异常"),
    INGEST_ERROR(HttpStatus.INTERNAL_SERVER_ERROR.value(), "文档解析失败"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部错误");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
