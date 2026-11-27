package com.carbonbal.common;

/** 业务异常：携带字符串错误码、HTTP 状态与可选明细。 */
public class BizException extends RuntimeException {
    private final String code;
    private final int http;
    private final Object detail;

    public BizException(String code, String message) {
        this(code, ErrorCode.http(code), message, null);
    }

    public BizException(String code, String message, Object detail) {
        this(code, ErrorCode.http(code), message, detail);
    }

    public BizException(String code, int http, String message, Object detail) {
        super(message);
        this.code = code;
        this.http = http;
        this.detail = detail;
    }

    public String getCode() { return code; }
    public int getHttp() { return http; }
    public Object getDetail() { return detail; }

    public static BizException validation(String m) { return new BizException(ErrorCode.VALIDATION, m); }
    public static BizException unauthorized(String m) { return new BizException(ErrorCode.UNAUTHORIZED, m); }
    public static BizException forbidden(String m) { return new BizException(ErrorCode.FORBIDDEN, m); }
    public static BizException notFound(String m) { return new BizException(ErrorCode.NOT_FOUND, m); }
    public static BizException notFound(String m, Object d) { return new BizException(ErrorCode.NOT_FOUND, m, d); }
    public static BizException duplicate(String m) { return new BizException(ErrorCode.DUPLICATE, m); }
    public static BizException duplicate(String m, Object d) { return new BizException(ErrorCode.DUPLICATE, m, d); }
    public static BizException referenced(String m, Object d) { return new BizException(ErrorCode.REFERENCED, m, d); }
    public static BizException conflict(String m, Object d) { return new BizException(ErrorCode.VERSION_CONFLICT, m, d); }
    public static BizException insufficient(String m, Object d) { return new BizException(ErrorCode.INSUFFICIENT, m, d); }
    public static BizException locked(String m) { return new BizException(ErrorCode.PERIOD_LOCKED, m); }
    public static BizException factorMissing(String m) { return new BizException(ErrorCode.FACTOR_MISSING, m); }
    public static BizException sod(String m) { return new BizException(ErrorCode.SOD_VIOLATION, m); }
    public static BizException selfOp(String m) { return new BizException(ErrorCode.SELF_OPERATION, m); }
    public static BizException pageForbidden() { return new BizException(ErrorCode.PAGE_FORBIDDEN, "没有该功能的访问权限"); }
    public static BizException pageForbidden(String m) { return new BizException(ErrorCode.PAGE_FORBIDDEN, m); }
    public static BizException internal(String m) { return new BizException(ErrorCode.INTERNAL, m); }
    public static BizException internal(String m, Object d) { return new BizException(ErrorCode.INTERNAL, m, d); }
}
