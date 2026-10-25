package com.collabhub.platform.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常。
 *
 * <p>约定：凡是「可以预期、需要告诉用户原因」的失败，都抛这个异常，
 * 由 {@link GlobalExceptionHandler} 统一转成 RFC 9457 的 ProblemDetail。
 *
 * <p>不要用这个异常表达程序错误（空指针、数据库连接失败之类），
 * 那些应该走 500 并由监控告警。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public BusinessException(String code, String message) {
        this(HttpStatus.BAD_REQUEST, code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
