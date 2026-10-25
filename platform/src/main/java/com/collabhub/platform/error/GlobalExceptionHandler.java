package com.collabhub.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理。
 *
 * <p>输出统一用 RFC 9457 的 {@code application/problem+json}，不再自己发明一套
 * {@code {code, msg, data}} 信封 —— 那是上一代的做法，标准格式好处是工具链和前端
 * 都能直接消费。
 *
 * <p>每个错误都带上 traceId，用户报错时贴这个 ID 就能在日志里定位到具体请求。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String TRACE_ID = "traceId";

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getCode());
        problem.setType(URI.create("urn:collabhub:error:" + ex.getCode()));
        problem.setProperty("code", ex.getCode());
        putTraceId(problem);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        // 真实项目里这里要接告警。注意：父类给的 logger 是 commons-logging，
        // 不支持 {} 占位符，只能字符串拼接
        logger.error("未处理异常 path=" + request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(500), "服务器内部错误，请凭 traceId 联系维护者");
        problem.setTitle("INTERNAL_ERROR");
        problem.setType(URI.create("urn:collabhub:error:INTERNAL_ERROR"));
        problem.setProperty("code", "INTERNAL_ERROR");
        putTraceId(problem);
        return problem;
    }

    /** 参数校验失败：把每个字段的错误告诉前端，别只说「参数错误」。 */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "请求参数校验失败");
        problem.setTitle("VALIDATION_FAILED");
        problem.setType(URI.create("urn:collabhub:error:VALIDATION_FAILED"));
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", fieldErrors);
        putTraceId(problem);

        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    private void putTraceId(ProblemDetail problem) {
        String traceId = MDC.get(TRACE_ID);
        if (traceId != null) {
            problem.setProperty(TRACE_ID, traceId);
        }
    }
}
