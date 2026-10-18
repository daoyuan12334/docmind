package com.docmind.common;

/** 业务异常：携带错误码，由 GlobalExceptionHandler 统一转 HTTP 状态。 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public static BizException notFound(String what) {
        return new BizException(ErrorCode.NOT_FOUND, what + "不存在");
    }

    public static BizException forbidden(String msg) {
        return new BizException(ErrorCode.FORBIDDEN, msg);
    }

    public static BizException badRequest(String msg) {
        return new BizException(ErrorCode.BAD_REQUEST, msg);
    }
}
