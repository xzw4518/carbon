package com.carbonbal.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常出口：客户端输入错误一律 4xx，只有真正的服务端故障才 500。
 * <p>
 * 约定：参数类型/缺失/请求体/上传解析 → 400；方法不支持 → 405；媒体类型不支持 → 415。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse> handleBiz(BizException e) {
        return ResponseEntity.status(e.getHttp())
                .body(ApiResponse.error(e.getCode(), e.getMessage(), e.getDetail()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleValid(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(ErrorCode.http(ErrorCode.VALIDATION))
                .body(ApiResponse.error(ErrorCode.VALIDATION, "参数校验失败", fields));
    }

    /** 参数类型不匹配：`?page=abc`、`?deviceId=abc`、日期格式错误等。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        return ResponseEntity.status(ErrorCode.http(ErrorCode.VALIDATION))
                .body(ApiResponse.error(ErrorCode.VALIDATION, "参数格式不正确", Map.of("field", name)));
    }

    /** 必填查询参数缺失：`/api/export` 不传 resource。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(ErrorCode.http(ErrorCode.VALIDATION))
                .body(ApiResponse.error(ErrorCode.VALIDATION,
                        "缺少必填参数：" + e.getParameterName(), Map.of("field", e.getParameterName())));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(ErrorCode.http(ErrorCode.METHOD_NOT_ALLOWED))
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED,
                        "该接口不支持 " + e.getMethod() + " 方法", Map.of("supported", e.getSupportedMethods())));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(ErrorCode.http(ErrorCode.UNSUPPORTED_MEDIA_TYPE))
                .body(ApiResponse.error(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        "不支持的内容类型，请检查 Content-Type", e.getContentType() == null ? null : e.getContentType().toString()));
    }

    /** multipart 里缺文件字段：直接 POST /api/files 不带文件。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.status(ErrorCode.http(ErrorCode.VALIDATION))
                .body(ApiResponse.error(ErrorCode.VALIDATION,
                        "缺少文件字段：" + e.getRequestPartName(), Map.of("field", e.getRequestPartName())));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse> handleUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(ErrorCode.http(ErrorCode.VALIDATION))
                .body(ApiResponse.error(ErrorCode.VALIDATION, "上传文件超过 10MB 限制", null));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse> handleMultipart(MultipartException e) {
        return ResponseEntity.status(ErrorCode.http(ErrorCode.VALIDATION))
                .body(ApiResponse.error(ErrorCode.VALIDATION, "文件上传解析失败，请使用 multipart/form-data", null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.http(ErrorCode.VALIDATION))
                .body(ApiResponse.error(ErrorCode.VALIDATION, "请求体格式不正确", null));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse> handleDenied(AccessDeniedException e) {
        return ResponseEntity.status(ErrorCode.http(ErrorCode.FORBIDDEN))
                .body(ApiResponse.error(ErrorCode.FORBIDDEN, "无权限执行该操作", null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(ErrorCode.http(ErrorCode.NOT_FOUND))
                .body(ApiResponse.error(ErrorCode.NOT_FOUND, "资源不存在", e.getResourcePath()));
    }

    /**
     * 数据库约束冲突兜底。未做前置校验的字段撞到唯一索引或列长度时，
     * 必须转成可读的 4xx，并把 SQL、表名、列名挡在响应之外。
     */
    /** 并发写冲突：死锁/锁等待超时按 409 返回，提示客户端重试，避免 5xx。 */
    @ExceptionHandler({org.springframework.dao.DeadlockLoserDataAccessException.class,
            org.springframework.dao.CannotAcquireLockException.class})
    public ResponseEntity<ApiResponse> handleLockConflict(Exception e, HttpServletRequest req) {
        log.warn("concurrent lock conflict {} {}: {}", req.getMethod(), req.getRequestURI(), rootMessage(e));
        return ResponseEntity.status(ErrorCode.http(ErrorCode.VERSION_CONFLICT))
                .body(ApiResponse.error(ErrorCode.VERSION_CONFLICT, "系统繁忙，请稍后重试", null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse> handleDataIntegrity(DataIntegrityViolationException e,
                                                           HttpServletRequest req) {
        String raw = rootMessage(e);
        log.warn("data integrity violated {} {}: {}", req.getMethod(), req.getRequestURI(), raw);
        if (raw.contains("Duplicate entry") || raw.contains("uk_")) {
            return ResponseEntity.status(ErrorCode.http(ErrorCode.DUPLICATE))
                    .body(ApiResponse.error(ErrorCode.DUPLICATE,
                            "该名称或编码已被占用；若刚删除过同名记录，请换一个名称或联系管理员清理", null));
        }
        if (raw.contains("Data too long") || raw.contains("Data truncation")) {
            return ResponseEntity.status(ErrorCode.http(ErrorCode.VALIDATION))
                    .body(ApiResponse.error(ErrorCode.VALIDATION, "输入内容超出字段长度限制，请精简后重试", null));
        }
        if (raw.contains("Out of range") || raw.contains("Incorrect decimal value")) {
            return ResponseEntity.status(ErrorCode.http(ErrorCode.VALIDATION))
                    .body(ApiResponse.error(ErrorCode.VALIDATION, "数值超出允许范围，请检查后重试", null));
        }
        if (raw.contains("cannot be null") || raw.contains("doesn't have a default value")) {
            return ResponseEntity.status(ErrorCode.http(ErrorCode.VALIDATION))
                    .body(ApiResponse.error(ErrorCode.VALIDATION, "存在必填字段未提交", null));
        }
        return ResponseEntity.status(ErrorCode.http(ErrorCode.VALIDATION))
                .body(ApiResponse.error(ErrorCode.VALIDATION, "数据未通过校验，请检查输入内容", null));
    }

    /** 只取最内层原因，避免把 MyBatis 的整段 SQL 拼进日志与响应。 */
    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? "" : m;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleOther(Exception e, HttpServletRequest req) {
        log.error("unhandled error {} {}", req.getMethod(), req.getRequestURI(), e);
        // 不返回 e.getMessage()：其中可能含 SQL、表名、类路径等内部结构
        return ResponseEntity.status(ErrorCode.http(ErrorCode.INTERNAL))
                .body(ApiResponse.error(ErrorCode.INTERNAL, "服务内部错误，请联系管理员", null));
    }
}
